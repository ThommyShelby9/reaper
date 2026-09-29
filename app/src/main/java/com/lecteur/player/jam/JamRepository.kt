package com.lecteur.player.jam

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import com.lecteur.player.account.AccountUser
import com.lecteur.player.account.await
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** Une jam telle que stockée dans Firestore. */
data class JamInfo(
    val id: String,
    val code: String,
    val hostUid: String,
    val hostName: String,
    val active: Boolean,
    val playback: JamPlayback?,
    /** Votes pour passer : clé du morceau visé et participants qui ont voté. */
    val skipVoteKey: String = "",
    val skipVoteUids: List<String> = emptyList(),
    /** File « chacun son tour » plutôt que par votes. */
    val fairQueue: Boolean = false,
    /** L'hôte enchaîne tout seul quand la file est vide. */
    val djMode: Boolean = true,
    val createdAtMs: Long = 0L,
)

enum class MemberStatus(val value: String) {
    Host("host"), Synced("synced"), Syncing("syncing"), Missing("missing"), Waiting("waiting");

    companion object {
        fun of(value: String?) = entries.firstOrNull { it.value == value } ?: Waiting
    }
}

data class JamMember(val uid: String, val name: String, val status: MemberStatus, val driftMs: Long?, val lastSeenMs: Long)

/** Une réaction envoyée par un participant. */
data class JamReaction(val id: String, val uid: String, val name: String)

/**
 * Lecture et écriture de la jam dans Firestore (voir firestore.rules) :
 * jams/{id}, jams/{id}/members/{uid}, jams/{id}/queue/{itemId}.
 */
class JamRepository {

    private val db = FirebaseFirestore.getInstance()
    private val jams = db.collection("jams")

    private fun jam(id: String) = jams.document(id)
    private fun member(jamId: String, uid: String) = jam(jamId).collection("members").document(uid)

    /** Crée une jam avec un code libre et y inscrit l'hôte ; renvoie l'identifiant de la jam. */
    suspend fun create(host: AccountUser): String {
        var code = generateJamCode()
        repeat(4) { if (findActiveJam(code) != null) code = generateJamCode() }
        val doc = jams.document()
        doc.set(
            mapOf(
                "hostUid" to host.uid,
                "hostName" to host.displayName,
                "code" to code,
                "active" to true,
                "createdAt" to FieldValue.serverTimestamp(),
                "playback" to null,
                "skipVotes" to mapOf("key" to "", "uids" to emptyList<String>()),
                "fairQueue" to false,
                "djMode" to true,
            ),
        ).await()
        join(doc.id, host, MemberStatus.Host)
        return doc.id
    }

    suspend fun findActiveJam(code: String): String? =
        jams.whereEqualTo("code", code).whereEqualTo("active", true).limit(1).get().await().documents.firstOrNull()?.id

    suspend fun join(jamId: String, user: AccountUser, status: MemberStatus) {
        member(jamId, user.uid).set(
            mapOf(
                "name" to user.displayName,
                "photoUrl" to user.photoUrl,
                "joinedAt" to FieldValue.serverTimestamp(),
                "lastSeen" to FieldValue.serverTimestamp(),
                "status" to status.value,
                "driftMs" to null,
            ),
        ).await()
    }

    suspend fun leave(jamId: String, uid: String) {
        member(jamId, uid).delete().await()
    }

    suspend fun end(jamId: String) {
        jam(jamId).update("active", false).await()
    }

    suspend fun heartbeat(jamId: String, uid: String, status: MemberStatus, driftMs: Long?) {
        member(jamId, uid).update(
            mapOf("lastSeen" to FieldValue.serverTimestamp(), "status" to status.value, "driftMs" to driftMs),
        ).await()
    }

    /**
     * Une mesure d'horloge : on écrit l'heure du serveur dans sa propre fiche, en notant l'heure locale
     * avant l'envoi et à la confirmation, puis on relit l'heure que le serveur a inscrite.
     */
    suspend fun clockSample(jamId: String, uid: String): ClockSample? {
        val ref = member(jamId, uid)
        val sent = System.currentTimeMillis()
        ref.update("clockProbe", FieldValue.serverTimestamp()).await()
        val received = System.currentTimeMillis()
        val server = ref.get(Source.SERVER).await().getTimestamp("clockProbe") ?: return null
        return ClockSample(sent, received, server.toDate().time)
    }

    suspend fun publish(jamId: String, playback: JamPlayback) {
        val track = playback.track
        jam(jamId).update(
            "playback",
            mapOf(
                "key" to track?.key,
                "title" to track?.title,
                "artist" to track?.artist,
                "album" to track?.album,
                "durationMs" to track?.durationMs,
                "playing" to playback.playing,
                "positionMs" to playback.positionMs,
                "anchorServerMs" to playback.anchorServerMs,
                "speed" to playback.speed.toDouble(),
            ),
        ).await()
    }

    suspend fun propose(jamId: String, user: AccountUser, track: JamTrackRef) {
        jam(jamId).collection("queue").add(
            mapOf(
                "key" to track.key,
                "title" to track.title,
                "artist" to track.artist,
                "album" to track.album,
                "durationMs" to track.durationMs,
                "addedBy" to user.uid,
                "addedByName" to user.displayName,
                "addedAt" to FieldValue.serverTimestamp(),
                "votes" to emptyList<String>(),
                "hostHas" to null,
            ),
        ).await()
    }

    suspend fun setVote(jamId: String, itemId: String, uid: String, voted: Boolean) {
        val change = if (voted) FieldValue.arrayUnion(uid) else FieldValue.arrayRemove(uid)
        jam(jamId).collection("queue").document(itemId).update("votes", change).await()
    }

    suspend fun setHostHas(jamId: String, itemId: String, has: Boolean) {
        jam(jamId).collection("queue").document(itemId).update("hostHas", has).await()
    }

    suspend fun removeItem(jamId: String, itemId: String) {
        jam(jamId).collection("queue").document(itemId).delete().await()
    }

    // --- Bibliothèque commune ---------------------------------------------------------------------

    /** Publie les empreintes de sa bibliothèque, par paquets (un document Firestore est limité à 1 Mo). */
    suspend fun publishLibrary(jamId: String, uid: String, hashes: List<String>) {
        val libraries = jam(jamId).collection("libraries")
        hashes.chunked(LIBRARY_CHUNK).ifEmpty { listOf(emptyList()) }.forEachIndexed { index, chunk ->
            libraries.document("${uid}_$index").set(mapOf("uid" to uid, "hashes" to chunk)).await()
        }
    }

    suspend fun deleteLibrary(jamId: String, uid: String) {
        val docs = jam(jamId).collection("libraries").whereEqualTo("uid", uid).get().await()
        docs.documents.forEach { it.reference.delete().await() }
    }

    /** Empreintes de chaque participant, regroupées par uid. */
    fun observeLibraries(jamId: String): Flow<Map<String, Set<String>>> = callbackFlow {
        val registration = jam(jamId).collection("libraries").addSnapshotListener { snapshot, _ ->
            val byUid = mutableMapOf<String, MutableSet<String>>()
            snapshot?.documents.orEmpty().forEach { doc ->
                val uid = doc.getString("uid") ?: return@forEach
                val hashes = (doc.get("hashes") as? List<*>).orEmpty().filterIsInstance<String>()
                byUid.getOrPut(uid) { mutableSetOf() }.addAll(hashes)
            }
            trySend(byUid)
        }
        awaitClose { registration.remove() }
    }

    // --- Vote pour passer, options, relais ---------------------------------------------------------

    /** L'hôte remet les votes à zéro pour un nouveau morceau. */
    suspend fun resetSkipVotes(jamId: String, key: String) {
        jam(jamId).update("skipVotes", mapOf("key" to key, "uids" to emptyList<String>())).await()
    }

    suspend fun setSkipVote(jamId: String, uid: String, voted: Boolean) {
        val change = if (voted) FieldValue.arrayUnion(uid) else FieldValue.arrayRemove(uid)
        jam(jamId).update("skipVotes.uids", change).await()
    }

    suspend fun setOption(jamId: String, field: String, value: Boolean) {
        jam(jamId).update(field, value).await()
    }

    /** Passer la main (l'hôte) ou la reprendre (hôte absent) : [user] devient l'hôte. */
    suspend fun setHost(jamId: String, user: JamMember) {
        jam(jamId).update(mapOf("hostUid" to user.uid, "hostName" to user.name)).await()
    }

    suspend fun markAbandoned(jamId: String) {
        jam(jamId).update("active", false).await()
    }

    /** Dernier signe de vie de l'hôte d'une jam (null s'il a quitté). */
    suspend fun hostLastSeenMs(jamId: String): Long? {
        val hostUid = jam(jamId).get().await().getString("hostUid") ?: return null
        return member(jamId, hostUid).get().await().getTimestamp("lastSeen")?.millis()
    }

    // --- Réactions --------------------------------------------------------------------------------

    suspend fun react(jamId: String, user: AccountUser): String {
        val doc = jam(jamId).collection("reactions").add(
            mapOf("uid" to user.uid, "name" to user.displayName, "at" to FieldValue.serverTimestamp()),
        ).await()
        return doc.id
    }

    suspend fun deleteReaction(jamId: String, reactionId: String) {
        jam(jamId).collection("reactions").document(reactionId).delete().await()
    }

    /** Nouvelles réactions uniquement (celles qui arrivent après l'ouverture de l'écoute). */
    fun observeReactions(jamId: String): Flow<JamReaction> = callbackFlow {
        var first = true
        val registration = jam(jamId).collection("reactions").addSnapshotListener { snapshot, _ ->
            val changes = snapshot?.documentChanges.orEmpty()
            if (!first) {
                changes.filter { it.type == com.google.firebase.firestore.DocumentChange.Type.ADDED }.forEach { change ->
                    val doc = change.document
                    trySend(JamReaction(doc.id, doc.getString("uid").orEmpty(), doc.getString("name") ?: "Quelqu'un"))
                }
            }
            first = false
        }
        awaitClose { registration.remove() }
    }

    // --- Journal des titres passés (récap) ----------------------------------------------------------

    suspend fun logPlayed(jamId: String, entry: PlayedEntry) {
        jam(jamId).collection("played").add(
            mapOf(
                "key" to entry.track.key,
                "title" to entry.track.title,
                "artist" to entry.track.artist,
                "album" to entry.track.album,
                "durationMs" to entry.track.durationMs,
                "addedByName" to entry.addedByName,
                "votes" to entry.votes,
                "playedAtMs" to entry.playedAtMs,
                "addedBy" to entry.addedBy,
            ),
        ).await()
    }

    suspend fun fetchPlayed(jamId: String): List<PlayedEntry> =
        jam(jamId).collection("played").get().await().documents.mapNotNull { doc ->
            val key = doc.getString("key") ?: return@mapNotNull null
            PlayedEntry(
                track = JamTrackRef(
                    key = key,
                    title = doc.getString("title").orEmpty(),
                    artist = doc.getString("artist").orEmpty(),
                    album = doc.getString("album").orEmpty(),
                    durationMs = doc.getLong("durationMs") ?: 0L,
                ),
                addedByName = doc.getString("addedByName"),
                votes = (doc.getLong("votes") ?: 0L).toInt(),
                playedAtMs = doc.getLong("playedAtMs") ?: 0L,
                addedBy = doc.getString("addedBy"),
            )
        }

    fun observeJam(jamId: String): Flow<JamInfo?> = callbackFlow {
        val registration = jam(jamId).addSnapshotListener { snapshot, error ->
            if (error != null) {
                trySend(null)
                return@addSnapshotListener
            }
            trySend(snapshot?.takeIf { it.exists() }?.toJamInfo())
        }
        awaitClose { registration.remove() }
    }

    fun observeMembers(jamId: String): Flow<List<JamMember>> = callbackFlow {
        val registration = jam(jamId).collection("members").addSnapshotListener { snapshot, _ ->
            trySend(snapshot?.documents.orEmpty().map { it.toMember() })
        }
        awaitClose { registration.remove() }
    }

    fun observeQueue(jamId: String): Flow<List<JamQueueItem>> = callbackFlow {
        val registration = jam(jamId).collection("queue").addSnapshotListener { snapshot, _ ->
            trySend(sortedQueue(snapshot?.documents.orEmpty().mapNotNull { it.toQueueItem() }))
        }
        awaitClose { registration.remove() }
    }

    private fun DocumentSnapshot.toJamInfo(): JamInfo {
        @Suppress("UNCHECKED_CAST")
        val p = get("playback") as? Map<String, Any?>
        val playback = p?.let {
            val key = it["key"] as? String
            JamPlayback(
                track = key?.let { k ->
                    JamTrackRef(
                        key = k,
                        title = it["title"] as? String ?: "",
                        artist = it["artist"] as? String ?: "",
                        album = it["album"] as? String ?: "",
                        durationMs = (it["durationMs"] as? Number)?.toLong() ?: 0L,
                    )
                },
                playing = it["playing"] as? Boolean ?: false,
                positionMs = (it["positionMs"] as? Number)?.toLong() ?: 0L,
                anchorServerMs = (it["anchorServerMs"] as? Number)?.toLong() ?: 0L,
                speed = (it["speed"] as? Number)?.toFloat() ?: 1f,
            )
        }
        return JamInfo(
            id = id,
            code = getString("code").orEmpty(),
            hostUid = getString("hostUid").orEmpty(),
            hostName = getString("hostName").orEmpty(),
            active = getBoolean("active") ?: false,
            playback = playback,
            skipVoteKey = (get("skipVotes.key") as? String).orEmpty(),
            skipVoteUids = (get("skipVotes.uids") as? List<*>).orEmpty().filterIsInstance<String>(),
            fairQueue = getBoolean("fairQueue") ?: false,
            djMode = getBoolean("djMode") ?: true,
            createdAtMs = getTimestamp("createdAt")?.millis() ?: 0L,
        )
    }

    private fun DocumentSnapshot.toMember() = JamMember(
        uid = id,
        name = getString("name") ?: "Sans nom",
        status = MemberStatus.of(getString("status")),
        driftMs = getLong("driftMs"),
        lastSeenMs = getTimestamp("lastSeen")?.millis() ?: System.currentTimeMillis(),
    )

    private fun DocumentSnapshot.toQueueItem(): JamQueueItem? {
        val key = getString("key") ?: return null
        val votes = (get("votes") as? List<*>).orEmpty().filterIsInstance<String>()
        return JamQueueItem(
            id = id,
            track = JamTrackRef(
                key = key,
                title = getString("title").orEmpty(),
                artist = getString("artist").orEmpty(),
                album = getString("album").orEmpty(),
                durationMs = getLong("durationMs") ?: 0L,
            ),
            addedBy = getString("addedBy").orEmpty(),
            addedByName = getString("addedByName") ?: "Quelqu'un",
            // Tant que le serveur n'a pas daté l'ajout, on le place en dernier.
            addedAtMs = getTimestamp("addedAt")?.millis() ?: Long.MAX_VALUE,
            votes = votes,
            hostHas = getBoolean("hostHas"),
        )
    }

    private fun Timestamp.millis() = toDate().time

    private companion object {
        /** Empreintes par document : 2 000 × 12 caractères, bien sous la limite de 1 Mo. */
        const val LIBRARY_CHUNK = 2_000
    }
}
