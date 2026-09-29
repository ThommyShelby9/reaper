package com.lecteur.player.account

import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.gms.tasks.Task
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.lecteur.player.R
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Le compte connecté, tel que l'app l'affiche. */
data class AccountUser(val uid: String, val displayName: String, val email: String?, val photoUrl: String?)

/** Résultat d'une tentative de connexion ; [message] est prêt à afficher, null si l'utilisateur a annulé. */
sealed interface SignInResult {
    data object Success : SignInResult
    data class Failure(val message: String?) : SignInResult
}

/**
 * Connexion par compte Google : Credential Manager fournit un jeton Google, Firebase Auth en fait un compte.
 * Un profil minimal (nom, photo) est enregistré dans Firestore pour que les autres participants d'une jam le voient.
 */
class AccountRepository(context: Context) {

    private val appContext = context.applicationContext
    private val auth = FirebaseAuth.getInstance()
    private val firestore = FirebaseFirestore.getInstance()
    private val credentials = CredentialManager.create(appContext)

    val user: Flow<AccountUser?> = callbackFlow {
        val listener = FirebaseAuth.AuthStateListener { trySend(it.currentUser?.toAccountUser()) }
        auth.addAuthStateListener(listener)
        awaitClose { auth.removeAuthStateListener(listener) }
    }

    /** [activityContext] doit être une activité : le sélecteur de compte s'affiche par-dessus. */
    suspend fun signInWithGoogle(activityContext: Context): SignInResult {
        val option = GetSignInWithGoogleOption.Builder(appContext.getString(R.string.default_web_client_id)).build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        return try {
            val credential = credentials.getCredential(activityContext, request).credential
            if (credential !is CustomCredential || credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                return SignInResult.Failure("Google n'a pas renvoyé d'identifiant utilisable. Réessayez.")
            }
            val google = GoogleIdTokenCredential.createFrom(credential.data)
            val result = auth.signInWithCredential(GoogleAuthProvider.getCredential(google.idToken, null)).await()
            val firebaseUser = result.user ?: return SignInResult.Failure("La connexion n'a pas abouti. Réessayez.")
            saveProfile(firebaseUser.toAccountUser(), isNew = result.additionalUserInfo?.isNewUser == true)
            SignInResult.Success
        } catch (_: GetCredentialCancellationException) {
            SignInResult.Failure(null)
        } catch (_: NoCredentialException) {
            SignInResult.Failure("Aucun compte Google sur ce téléphone. Ajoutez-en un dans les réglages d'Android, puis réessayez.")
        } catch (e: GetCredentialException) {
            SignInResult.Failure("La connexion Google a échoué (${e.type}). Vérifiez votre connexion internet et réessayez.")
        } catch (e: Exception) {
            SignInResult.Failure("La connexion a échoué : ${e.localizedMessage ?: "erreur inconnue"}.")
        }
    }

    suspend fun signOut() {
        auth.signOut()
        runCatching { credentials.clearCredentialState(ClearCredentialStateRequest()) }
    }

    private suspend fun saveProfile(user: AccountUser, isNew: Boolean) {
        val data = buildMap<String, Any?> {
            put("displayName", user.displayName)
            put("photoUrl", user.photoUrl)
            put("updatedAt", FieldValue.serverTimestamp())
            if (isNew) put("createdAt", FieldValue.serverTimestamp())
        }
        // Le profil n'est pas indispensable pour être connecté : un échec ici ne bloque pas.
        runCatching { firestore.collection("users").document(user.uid).set(data, SetOptions.merge()).await() }
    }

    private fun com.google.firebase.auth.FirebaseUser.toAccountUser() = AccountUser(
        uid = uid,
        displayName = displayName?.takeIf { it.isNotBlank() } ?: email?.substringBefore('@') ?: "Sans nom",
        email = email,
        photoUrl = photoUrl?.toString(),
    )
}

/** Attend la fin d'une tâche Google Play Services sans dépendance supplémentaire. */
suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnCompleteListener { task ->
        val error = task.exception
        when {
            error != null -> cont.resumeWithException(error)
            task.isCanceled -> cont.cancel()
            else -> cont.resume(task.result)
        }
    }
}
