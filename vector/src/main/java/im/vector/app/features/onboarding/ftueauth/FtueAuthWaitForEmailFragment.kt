/*
 * Copyright 2019 New Vector Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package im.vector.app.features.onboarding.ftueauth

import android.os.Bundle
import android.os.Parcelable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import com.airbnb.mvrx.args
import im.vector.app.R
import im.vector.app.core.utils.colorTerminatingFullStop
import im.vector.app.databinding.FragmentFtueWaitForEmailVerificationBinding
import im.vector.app.features.onboarding.OnboardingAction
import im.vector.app.features.onboarding.RegisterAction
import im.vector.app.features.themes.ThemeProvider
import im.vector.app.features.themes.ThemeUtils
import kotlinx.parcelize.Parcelize
import javax.inject.Inject

@Parcelize
data class FtueAuthWaitForEmailFragmentArgument(
        val email: String
) : Parcelable

/**
 * In this screen, the user is asked to check their emails.
 */
class FtueAuthWaitForEmailFragment @Inject constructor(
        private val themeProvider: ThemeProvider
) : AbstractFtueAuthFragment<FragmentFtueWaitForEmailVerificationBinding>() {

    private val params: FtueAuthWaitForEmailFragmentArgument by args()
    private var inferHasLeftAndReturnedToScreen = false

    override fun getBinding(inflater: LayoutInflater, container: ViewGroup?): FragmentFtueWaitForEmailVerificationBinding {
        return FragmentFtueWaitForEmailVerificationBinding.inflate(inflater, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupUi()
    }

    private fun setupUi() {
        views.emailVerificationGradientContainer.setBackgroundResource(
                when (themeProvider.isLightTheme()) {
                    true  -> R.drawable.bg_waiting_for_email_verification
                    false -> R.drawable.bg_color_background
                }
        )
        views.emailVerificationTitle.text = getString(R.string.ftue_auth_email_verification_title)
                .colorTerminatingFullStop(ThemeUtils.getColor(requireContext(), R.attr.colorSecondary))
        views.emailVerificationSubtitle.text = getString(R.string.ftue_auth_email_verification_subtitle, params.email)
        views.emailVerificationResendEmail.debouncedClicks {
            viewModel.handle(OnboardingAction.PostRegisterAction(RegisterAction.SendAgainThreePid))
        }
    }

    override fun onResume() {
        super.onResume()
        showLoadingIfReturningToScreen()
        viewModel.handle(OnboardingAction.PostRegisterAction(RegisterAction.CheckIfEmailHasBeenValidated(0)))
    }

    private fun showLoadingIfReturningToScreen() {
        when (inferHasLeftAndReturnedToScreen) {
            true  -> views.emailVerificationWaiting.isVisible = true
            false -> {
                inferHasLeftAndReturnedToScreen = true
            }
        }
    }

    override fun onPause() {
        super.onPause()
        viewModel.handle(OnboardingAction.StopEmailValidationCheck)
    }

    override fun resetViewModel() {
        viewModel.handle(OnboardingAction.ResetAuthenticationAttempt)
    }
}
