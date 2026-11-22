package com.screentrans.app

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** 설정 화면 + 시작 버튼 (화면을 코드로 만들어 의존성을 줄임) */
class MainActivity : Activity() {
    private lateinit var prefs: Prefs
    private val REQ_CAPTURE = 11

    private lateinit var engine: RadioGroup
    private lateinit var key: EditText
    private lateinit var model: RadioGroup
    private lateinit var sendImage: CheckBox
    private lateinit var memo: EditText
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(20), px(28), px(20), px(40)) }
        fun title(t: String, size: Float = 15f) = TextView(this).apply { text = t; textSize = size; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.rgb(20, 22, 26)); setPadding(0, px(18), 0, px(6)) }
        fun note(t: String) = TextView(this).apply { text = t; textSize = 13f; setTextColor(Color.rgb(100, 100, 100)); setPadding(0, 0, 0, px(4)) }

        col.addView(title("화면 번역", 26f).apply { setPadding(0, 0, 0, px(4)) })
        col.addView(note("다른 앱 위에 떠 있는 버튼으로 지금 보는 화면을 바로 번역해요.\n· 탭: 번역 (다시 탭하면 지우기)\n· 두 번 탭: Claude로 번역\n· 길게 누르기: 이 설정 화면 열기\n· 끌어서 위치 옮기기"))

        col.addView(title("기본 번역"))
        engine = RadioGroup(this)
        val rFree = RadioButton(this).apply { id = 101; text = "무료 (구글 폰 내장 번역)" }
        val rClaude = RadioButton(this).apply { id = 102; text = "Claude (자연스러움, API 요금)" }
        engine.addView(rFree); engine.addView(rClaude)
        engine.check(if (prefs.engine == "claude") 102 else 101)
        col.addView(engine)
        col.addView(note("무료 번역은 처음 한 번 일본어·영어 번역 모델(각 약 30MB)을 받아요."))

        col.addView(title("Claude API 키"))
        key = EditText(this).apply { hint = "sk-ant-..."; setText(prefs.apiKey); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD; isSingleLine = true }
        col.addView(key)
        col.addView(note("console.anthropic.com 에서 발급. 이 폰에만 저장돼요. 키가 없으면 무료 번역만 써요."))

        col.addView(title("Claude 모델"))
        model = RadioGroup(this)
        model.addView(RadioButton(this).apply { id = 201; text = "빠르고 저렴 (Haiku)" })
        model.addView(RadioButton(this).apply { id = 202; text = "더 자연스러움 (Sonnet)" })
        model.check(if (prefs.model.contains("sonnet")) 202 else 201)
        col.addView(model)
        sendImage = CheckBox(this).apply { text = "화면 이미지도 같이 보내기 (장면을 보고 번역, 비용 조금 증가)"; isChecked = prefs.sendImage }
        col.addView(sendImage)
        col.addView(note("Claude가 번역하지 않는 부분(거절 등)은 자동으로 무료 번역으로 채워요."))

        col.addView(title("참고 메모 (Claude용, 선택)"))
        memo = EditText(this).apply { hint = "예: 주인공 이름은 렌, 히로인에겐 반말"; setText(prefs.memo); minLines = 2 }
        col.addView(memo)

        val start = Button(this).apply { text = "시작"; textSize = 18f; setOnClickListener { save(); startFlow() } }
        val stop = Button(this).apply { text = "끄기"; setOnClickListener { stopService(Intent(this@MainActivity, TranslateService::class.java)); refresh() } }
        col.addView(start, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(56)).apply { topMargin = px(24) })
        col.addView(stop, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(48)).apply { topMargin = px(8) })
        status = note("").apply { gravity = Gravity.CENTER; setPadding(0, px(12), 0, 0) }
        col.addView(status)
        col.addView(note("\n시작하면 '화면 공유/녹화' 확인 창이 떠요. '전체 화면'을 골라주세요. 화면은 번역에만 쓰이고 저장되지 않아요."))

        setContentView(ScrollView(this).apply { setBackgroundColor(Color.rgb(246, 244, 239)); addView(col) })
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun refresh() {
        status.text = if (TranslateService.running) "실행 중이에요. 다른 앱으로 가서 버튼을 눌러보세요." else ""
    }

    private fun save() {
        prefs.engine = if (engine.checkedRadioButtonId == 102) "claude" else "free"
        prefs.apiKey = key.text.toString()
        prefs.model = if (model.checkedRadioButtonId == 202) "claude-sonnet-5-5" else "claude-haiku-5-5"
        prefs.sendImage = sendImage.isChecked
        prefs.memo = memo.text.toString()
        if (prefs.engine == "claude" && !prefs.hasClaude) Toast.makeText(this, "API 키가 없어서 무료 번역으로 동작해요", Toast.LENGTH_LONG).show()
    }

    private fun startFlow() {
        // 1) 다른 앱 위에 그리기 권한
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "'다른 앱 위에 표시'를 허용한 뒤 다시 시작을 눌러주세요", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        // 2) 알림 권한 (안드로이드 13+): 실행 중 알림에 '끄기' 버튼이 있음
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 5)
        }
        // 3) 화면 캡처 동의
        val mpm = getSystemService(MediaProjectionManager::class.java)
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE)
    }

    @Deprecated("Activity result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CAPTURE) return
        if (resultCode != RESULT_OK || data == null) { Toast.makeText(this, "화면 캡처를 허용해야 번역할 수 있어요", Toast.LENGTH_LONG).show(); return }
        val i = Intent(this, TranslateService::class.java).putExtra(TranslateService.EXTRA_CODE, resultCode).putExtra(TranslateService.EXTRA_DATA, data)
        startForegroundService(i)
        moveTaskToBack(true)   // 홈으로: 번역할 앱으로 가면 돼요
    }
}
