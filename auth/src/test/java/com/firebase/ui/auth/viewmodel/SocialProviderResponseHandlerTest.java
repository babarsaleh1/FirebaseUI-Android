package com.firebase.ui.auth.viewmodel;

import android.app.Activity;
import android.app.Application;
import android.os.Looper;

import com.firebase.ui.auth.AuthUI;
import com.firebase.ui.auth.ErrorCodes;
import com.firebase.ui.auth.FirebaseAuthAnonymousUpgradeException;
import com.firebase.ui.auth.IdpResponse;
import com.firebase.ui.auth.data.model.FlowParameters;
import com.firebase.ui.auth.data.model.IntentRequiredException;
import com.firebase.ui.auth.data.model.Resource;
import com.firebase.ui.auth.data.model.User;
import com.firebase.ui.auth.testhelpers.AutoCompleteTask;
import com.firebase.ui.auth.testhelpers.FakeAuthResult;
import com.firebase.ui.auth.testhelpers.FakeSignInMethodQueryResult;
import com.firebase.ui.auth.testhelpers.ResourceMatchers;
import com.firebase.ui.auth.testhelpers.TestConstants;
import com.firebase.ui.auth.testhelpers.TestHelper;
import com.firebase.ui.auth.ui.email.WelcomeBackPasswordPrompt;
import com.firebase.ui.auth.ui.idp.WelcomeBackIdpPrompt;
import com.firebase.ui.auth.viewmodel.idp.SocialProviderResponseHandler;
import com.firebase.ui.auth.viewmodel.credentialmanager.CredentialManagerHandler;
import com.google.firebase.auth.AuthCredential;
import com.google.firebase.auth.EmailAuthProvider;
import com.google.firebase.auth.FacebookAuthProvider;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.FirebaseAuthUserCollisionException;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.GoogleAuthProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.LooperMode;

import java.util.Arrays;
import java.util.Collections;

import androidx.lifecycle.Observer;
import androidx.test.core.app.ApplicationProvider;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

/**
 * Unit tests for {@link CredentialManagerHandler}.
 */
@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class SocialProviderResponseHandlerTest {
    @Mock FirebaseAuth mMockAuth;
    @Mock FirebaseUser mUser;
    @Mock Observer<Resource<IdpResponse>> mResultObserver;

    private SocialProviderResponseHandler mHandler;

    @Before
    public void setUp() {
        TestHelper.initialize();
        MockitoAnnotations.initMocks(this);

        mHandler = new SocialProviderResponseHandler((Application) ApplicationProvider.getApplicationContext());
        FlowParameters testParams = TestHelper.getFlowParameters(AuthUI.SUPPORTED_PROVIDERS);

        mHandler.initializeForTesting(testParams, mMockAuth, null);
    }

    @Test
    public void testSignInIdp_success() {
        mHandler.getOperation().observeForever(mResultObserver);

        when(mMockAuth.signInWithCredential(any(AuthCredential.class)))
                .thenReturn(AutoCompleteTask.forSuccess(FakeAuthResult.INSTANCE));

        IdpResponse response = new IdpResponse.Builder(new User.Builder(
                GoogleAuthProvider.PROVIDER_ID, TestConstants.EMAIL).build())
                .setToken(TestConstants.TOKEN)
                .build();

        mHandler.startSignIn(response);
        shadowOf(Looper.getMainLooper()).idle();

        verify(mMockAuth).signInWithCredential(any(AuthCredential.class));

        InOrder inOrder = inOrder(mResultObserver);
        inOrder.verify(mResultObserver)
                .onChanged(argThat(ResourceMatchers.isLoading()));
        inOrder.verify(mResultObserver)
                .onChanged(argThat(ResourceMatchers.isSuccess()));
    }

    @Test(expected = IllegalStateException.class)
    public void testSignInNonIdp_failure() {
        mHandler.getOperation().observeForever(mResultObserver);

        IdpResponse response = new IdpResponse.Builder(new User.Builder(
                EmailAuthProvider.PROVIDER_ID, TestConstants.EMAIL).build())
                .build();

        mHandler.startSignIn(response);
    }

    @Test
    public void testSignInResponse_failure() {
        mHandler.getOperation().observeForever(mResultObserver);

        IdpResponse response = IdpResponse.from(new Exception("Failure"));

        mHandler.startSignIn(response);

        verify(mResultObserver).onChanged(argThat(ResourceMatchers.isFailure()));
    }

    @Test
    public void testSignInIdp_disabled() {
        mHandler.getOperation().observeForever(mResultObserver);

        when(mMockAuth.signInWithCredential(any(AuthCredential.class)))
                .thenReturn(AutoCompleteTask.forFailure(
                        new FirebaseAuthException("ERROR_USER_DISABLED", "disabled")));

        IdpResponse response = new IdpResponse.Builder(new User.Builder(
                GoogleAuthProvider.PROVIDER_ID, TestConstants.EMAIL).build())
                .setToken(TestConstants.TOKEN)
                .build();
        mHandler.startSignIn(response);
        shadowOf(Looper.getMainLooper()).idle();

        verify(mResultObserver).onChanged(
                argThat(ResourceMatchers.isFailureWithCode(ErrorCodes.ERROR_USER_DISABLED)));
    }

    @Test
    public void testSignInIdp_resolution() {
        mHandler.getOperation().observeForever(mResultObserver);

        when(mMockAuth.signInWithCredential(any(AuthCredential.class)))
                .thenReturn(AutoCompleteTask.forFailure(
                        new FirebaseAuthUserCollisionException("foo", "bar")));
        when(mMockAuth.fetchSignInMethodsForEmail(any(String.class)))
                .thenReturn(AutoCompleteTask.forSuccess(
                        new FakeSignInMethodQueryResult(Collections.singletonList(
                                FacebookAuthProvider.FACEBOOK_SIGN_IN_METHOD))));

        IdpResponse response = new IdpResponse.Builder(new User.Builder(
                GoogleAuthProvider.PROVIDER_ID, TestConstants.EMAIL).build())
                .setToken(TestConstants.TOKEN)
                .build();

        mHandler.startSignIn(response);
        shadowOf(Looper.getMainLooper()).idle();

        verify(mMockAuth).signInWithCredential(any(AuthCredential.class));
        verify(mMockAuth).fetchSignInMethodsForEmail(any(String.class));

        InOrder inOrder = inOrder(mResultObserver);
        inOrder.verify(mResultObserver)
                .onChanged(argThat(ResourceMatchers.isLoading()));

        ArgumentCaptor<Resource<IdpResponse>> resolveCaptor =
                ArgumentCaptor.forClass(Resource.class);
        inOrder.verify(mResultObserver).onChanged(resolveCaptor.capture());

        // Call activity result
        IntentRequiredException e =
                ((IntentRequiredException) resolveCaptor.getValue().getException());
        mHandler.onActivityResult(e.getRequestCode(), Activity.RESULT_OK, response.toIntent());

        // Make sure we get success
        inOrder.verify(mResultObserver)
                .onChanged(argThat(ResourceMatchers.isSuccess()));
    }


    @Test
    public void testSignInIdp_anonymousUserUpgradeEnabledAndNewUser_expectSuccess() {
        mHandler.getOperation().observeForever(mResultObserver);
        setupAnonymousUpgrade();

        when(mMockAuth.getCurrentUser().linkWithCredential(any(AuthCredential.class)))
                .thenReturn(AutoCompleteTask.forSuccess(FakeAuthResult.INSTANCE));

        IdpResponse response = new IdpResponse.Builder(new User.Builder(
                GoogleAuthProvider.PROVIDER_ID, TestConstants.EMAIL).build())
                .setToken(TestConstants.TOKEN)
                .build();

        mHandler.startSignIn(response);
        shadowOf(Looper.getMainLooper()).idle();

        verify(mMockAuth.getCurrentUser()).linkWithCredential(any(AuthCredential.class));

        InOrder inOrder = inOrder(mResultObserver);
        inOrder.verify(mResultObserver)
                .onChanged(argThat(ResourceMatchers.isLoading()));
        inOrder.verify(mResultObserver)
                .onChanged(argThat(ResourceMatchers.isSuccess()));
    }

    @Test
    public void testSignInIdp_anonymousUserUpgradeEnabledAndExistingUserWithSameIdp_expectMergeFailure() {
        mHandler.getOperation().observeForever(mResultObserver);
        setupAnonymousUpgrade();

        when(mMockAuth.getCurrentUser().linkWithCredential(any(AuthCredential.class)))
                .thenReturn(AutoCompleteTask.forFailure(
                        new FirebaseAuthUserCollisionException("foo", "bar")));

        // Case 1: Anon user signing in with a Google credential that belongs to an existing user.
        when(mMockAuth.fetchSignInMethodsForEmail(any(String.class)))
                .thenReturn(AutoCompleteTask.forSuccess(
                        new FakeSignInMethodQueryResult(Arrays.asList(
                                GoogleAuthProvider.GOOGLE_SIGN_IN_METHOD,
                                FacebookAuthProvider.FACEBOOK_SIGN_IN_METHOD))));


        IdpResponse response = new IdpResponse.Builder(new User.Builder(
                GoogleAuthProvider.PROVIDER_ID, TestConstants.EMAIL).build())
                .setToken(TestConstants.TOKEN)
                .build();

        mHandler.startSignIn(response);
        shadowOf(Looper.getMainLooper()).idle();

        verify(mMockAuth.getCurrentUser()).linkWithCredential(any(AuthCredential.class));

        InOrder inOrder = inOrder(mResultObserver);
        inOrder.verify(mResultObserver)
                .onChanged(argThat(ResourceMatchers.isLoading()));

        ArgumentCaptor<Resource<IdpResponse>> resolveCaptor =
                ArgumentCaptor.forClass(Resource.class);
        inOrder.verify(mResultObserver).onChanged(resolveCaptor.capture());

        FirebaseAuthAnonymousUpgradeException e =
                (FirebaseAuthAnonymousUpgradeException) resolveCaptor.getValue().getException();

        assertThat(e.getResponse().getCredentialForLinking()).isNotNull();
    }

    @Test
    public void testSignInIdp_anonymousUserUpgradeEnabledAndExistingIdpUserWithDifferentIdp_expectMergeFailure() {
        mHandler.getOperation().observeForever(mResultObserver);
        setupAnonymousUpgrade();

        when(mMockAuth.getCurrentUser().linkWithCredential(any(AuthCredential.class)))
                .thenReturn(AutoCompleteTask.forFailure(
                        new FirebaseAuthUserCollisionException("foo", "bar")));

        // Case 2 & 3: trying to link with an account that has 1 idp, which is different from the
        // one that we're trying to log in with
        when(mMockAuth.fetchSignInMethodsForEmail(any(String.class)))
                .thenReturn(AutoCompleteTask.forSuccess(
                        new FakeSignInMethodQueryResult(Collections.singletonList(
                                FacebookAuthProvider.FACEBOOK_SIGN_IN_METHOD))));

        IdpResponse response = new IdpResponse.Builder(new User.Builder(
                GoogleAuthProvider.PROVIDER_ID, TestConstants.EMAIL).build())
                .setToken(TestConstants.TOKEN)
                .build();

        mHandler.startSignIn(response);
        shadowOf(Looper.getMainLooper()).idle();

        verify(mMockAuth.getCurrentUser()).linkWithCredential(any(AuthCredential.class));

        InOrder inOrder = inOrder(mResultObserver);
        inOrder.verify(mResultObserver)
                .onChanged(argThat(ResourceMatchers.isLoading()));

        ArgumentCaptor<Resource<IdpResponse>> resolveCaptor =
                ArgumentCaptor.forClass(Resource.class);
        inOrder.verify(mResultObserver).onChanged(resolveCaptor.capture());

        // Make sure that we are trying to start the WelcomeBackIdpPrompt activity
        IntentRequiredException e =
                ((IntentRequiredException) resolveCaptor.getValue().getException());
        assertThat(e.getIntent().getComponent().getClassName())
                .isEqualTo(WelcomeBackIdpPrompt.class.toString().split(" ")[1]);

        assertThat(IdpResponse.fromResultIntent(e.getIntent())).isEqualTo(response);

    }

    @Test
    public void testSignInIdp_anonymousUserUpgradeEnabledAndExistingPasswordUserWithDifferentIdp_expectMergeFailure() {
        mHandler.getOperation().observeForever(mResultObserver);
        setupAnonymousUpgrade();

        when(mMockAuth.getCurrentUser().linkWithCredential(any(AuthCredential.class)))
                .thenReturn(AutoCompleteTask.forFailure(
                        new FirebaseAuthUserCollisionException("foo", "bar")));

        // Case 2 & 3: trying to link with an account that has 1 password provider and logging in
        // with an idp that has the same email
        when(mMockAuth.fetchSignInMethodsForEmail(any(String.class)))
                .thenReturn(AutoCompleteTask.forSuccess(
                        new FakeSignInMethodQueryResult(Collections.singletonList(
                                EmailAuthProvider.EMAIL_PASSWORD_SIGN_IN_METHOD))));

        IdpResponse response = new IdpResponse.Builder(new User.Builder(
                FacebookAuthProvider.PROVIDER_ID, TestConstants.EMAIL).build())
                .setToken(TestConstants.TOKEN)
                .setSecret(TestConstants.SECRET)
                .build();

        mHandler.startSignIn(response);
        shadowOf(Looper.getMainLooper()).idle();

        verify(mMockAuth.getCurrentUser()).linkWithCredential(any(AuthCredential.class));

        InOrder inOrder = inOrder(mResultObserver);
        inOrder.verify(mResultObserver)
                .onChanged(argThat(ResourceMatchers.isLoading()));

        ArgumentCaptor<Resource<IdpResponse>> resolveCaptor =
                ArgumentCaptor.forClass(Resource.class);
        inOrder.verify(mResultObserver).onChanged(resolveCaptor.capture());

        // Make sure that we are trying to start the WelcomeBackIdpPrompt activity
        IntentRequiredException e =
                ((IntentRequiredException) resolveCaptor.getValue().getException());
        assertThat(e.getIntent().getComponent().getClassName())
                .isEqualTo(WelcomeBackPasswordPrompt.class.toString().split(" ")[1]);

        assertThat(IdpResponse.fromResultIntent(e.getIntent())).isEqualTo(response);
    }

    private void setupAnonymousUpgrade() {
        // enableAnonymousUpgrade must be set to true
        FlowParameters testParams = TestHelper.getFlowParameters(AuthUI.SUPPORTED_PROVIDERS,
                /* enableAnonymousUpgrade */ true);
        mHandler.initializeForTesting(testParams, mMockAuth, null);

        when(mUser.isAnonymous()).thenReturn(true);
        when(mMockAuth.getCurrentUser()).thenReturn(mUser);
    }

}
