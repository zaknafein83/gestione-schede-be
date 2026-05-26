package it.fsisca.dndsheets.auth;

import it.fsisca.dndsheets.auth.dto.ForgotPasswordRequest;
import it.fsisca.dndsheets.auth.dto.GoogleLoginRequest;
import it.fsisca.dndsheets.auth.dto.LoginRequest;
import it.fsisca.dndsheets.auth.dto.LoginResponse;
import it.fsisca.dndsheets.auth.dto.RefreshRequest;
import it.fsisca.dndsheets.auth.dto.RegisterRequest;
import it.fsisca.dndsheets.auth.dto.ResetPasswordRequest;
import it.fsisca.dndsheets.auth.dto.UserResponse;
import it.fsisca.dndsheets.auth.dto.VerifyEmailRequest;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

@Path("/auth")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "auth", description = "Registrazione, verifica email, login")
public class AuthResource {

    @Inject AuthService authService;

    @POST
    @Path("/register")
    public Response register(@Valid RegisterRequest req) {
        User u = authService.register(req);
        return Response.status(Response.Status.CREATED)
                .entity(UserResponse.from(u))
                .build();
    }

    @POST
    @Path("/verify-email")
    public UserResponse verifyEmail(@Valid VerifyEmailRequest req) {
        return UserResponse.from(authService.verifyEmail(req.token()));
    }

    @POST
    @Path("/login")
    public LoginResponse login(@Valid LoginRequest req) {
        return authService.login(req);
    }

    /**
     * Login/registrazione via Google OIDC. Riceve l'ID token ottenuto dal
     * client tramite Google Sign-In, lo verifica e ritorna la stessa
     * {@link LoginResponse} di {@link #login}. Vedi {@link AuthService#googleLogin}
     * per la strategia di matching/auto-link.
     */
    @POST
    @Path("/google")
    public LoginResponse googleLogin(@Valid GoogleLoginRequest req) {
        return authService.googleLogin(req);
    }

    @POST
    @Path("/refresh")
    public LoginResponse refresh(@Valid RefreshRequest req) {
        return authService.refresh(req.refreshToken());
    }

    @POST
    @Path("/logout")
    public Response logout(@Valid RefreshRequest req) {
        authService.logout(req.refreshToken());
        return Response.noContent().build();
    }

    /**
     * Avvia il reset password. Risposta SEMPRE 204 (anche se l'email non
     * esiste): non riveliamo mai al caller se l'account c'è o meno.
     */
    @POST
    @Path("/forgot-password")
    public Response forgotPassword(@Valid ForgotPasswordRequest req) {
        authService.forgotPassword(req.email());
        return Response.noContent().build();
    }

    /**
     * Imposta una nuova password consumando il token ricevuto via email.
     * Revoca tutti i refresh token attivi dell'utente.
     */
    @POST
    @Path("/reset-password")
    public Response resetPassword(@Valid ResetPasswordRequest req) {
        authService.resetPassword(req.token(), req.password());
        return Response.noContent().build();
    }
}
