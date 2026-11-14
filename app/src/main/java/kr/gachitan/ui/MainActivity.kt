package kr.gachitan.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kr.gachitan.GachitanApp
import kr.gachitan.ui.screens.GachitanRoot
import kr.gachitan.ui.theme.GachitanTheme

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                MainViewModel((application as GachitanApp).container) as T
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Pleos CCP 가이드: 최상위 화면에서 Back이 오면 앱을 종료하지 않고 숨깁니다(moveTaskToBack).
        // 운행 기록은 앱 프로세스에서 계속됩니다. 설정·묶음·정산 확인에서는 이전 화면으로 돌아갑니다.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val ui = vm.ui.value
                when {
                    ui.screen == Screen.SETTINGS -> vm.closeSettings()
                    ui.screen == Screen.BUNDLE -> vm.closeBundle()
                    ui.showNav && ui.tab != Tab.BOARD -> vm.selectTab(Tab.BOARD)
                    else -> moveTaskToBack(true)
                }
            }
        })

        setContent {
            val ui by vm.ui.collectAsStateWithLifecycle()
            GachitanTheme(ui.settings.themeMode) {
                GachitanRoot(ui, vm)
            }
        }
    }
}
