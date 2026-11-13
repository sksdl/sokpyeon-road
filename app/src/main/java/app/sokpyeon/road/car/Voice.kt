package app.sokpyeon.road.car

import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.listener.OnInitializeListener as SttInit
import ai.pleos.playground.stt.listener.ResultListener
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener as TtsInit
import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.Locale

/**
 * 음성 안내(TTS)와 "속 안 좋아" 음성 인식(STT), 굽이 직전 좌우 알림음.
 * 앱 등록이 필요 없는 ON_DEVICE 모드를 쓴다.
 */
class Voice(private val context: Context, private val log: (String) -> Unit, private val onHeard: (text: String, final: Boolean) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var stt: SpeechToText? = null
    private var ttsReady = false
    private var sttReady = false
    private val tone = try { ToneGenerator(AudioManager.STREAM_MUSIC, 80) } catch (t: Throwable) { null }

    val ttsOk get() = ttsReady
    val sttOk get() = sttReady

    fun start() {
        try {
            val t = TextToSpeech(context, ai.pleos.playground.tts.constant.Mode.ON_DEVICE)
            val ttsEvents = object : EventListener {
                override fun onStart() { Log.i(CarLink.TAG, "TTS onStart") }
                override fun onReady() {}
                override fun onDone() { Log.i(CarLink.TAG, "TTS onDone") }
                override fun onError(error: ai.pleos.playground.tts.constant.ErrorType) = say("TTS 오류: $error")
                override fun onStop() {}
                override fun onUpdatedRms(rms: Double) {}
            }
            t.initialize(object : TtsInit {
                override fun onSuccess() { ttsReady = true; t.addEventListener(ttsEvents); try { t.setLocale(Locale.KOREA) } catch (_: Throwable) {}; say("TTS 준비됨(온디바이스)") }
                override fun onFailure(error: ai.pleos.playground.tts.constant.ErrorType) = say("TTS 초기화 실패: $error")
            })
            tts = t
        } catch (t: Throwable) {
            say("TTS 시작 실패: ${t.javaClass.simpleName} ${t.message}")
        }
        try {
            val s = SpeechToText(context, ai.pleos.playground.stt.constant.Mode.ON_DEVICE)
            val sttEvents = object : ResultListener {
                override fun onUpdated(text: String, isFinal: Boolean) { main.post { onHeard(text, isFinal) } }
                override fun onUpdatedEpdData(a: Long, b: Long) {}
                override fun onStartedRecognition() = say("STT: 듣는 중")
                override fun onEndedRecognition() = say("STT: 인식 끝")
                override fun onError(error: ai.pleos.playground.stt.constant.ErrorType) = say("STT 오류: $error")
                override fun onReady() {}
                override fun onUpdatedRms(rms: Double) {}
            }
            s.initialize(object : SttInit {
                override fun onSuccess() { sttReady = true; s.addListener(sttEvents); try { s.setLocale(Locale.KOREA) } catch (_: Throwable) {}; say("STT 준비됨(온디바이스)") }
                override fun onFailure(error: ai.pleos.playground.stt.constant.ErrorType) = say("STT 초기화 실패: $error")
            })
            stt = s
        } catch (t: Throwable) {
            say("STT 시작 실패: ${t.javaClass.simpleName} ${t.message}")
        }
    }

    fun speak(text: String) {
        val t = tts
        if (t == null || !ttsReady) { Log.i(CarLink.TAG, "TTS 미준비, 화면에만 표시: $text"); return }
        try { t.speak(text, true) } catch (e: Throwable) { say("TTS 재생 실패: ${e.message}") }
    }

    /** 굽이 직전 알림음. 왼쪽은 한 번, 오른쪽은 두 번 울린다. 화면 화살표와 같이 쓴다. */
    fun chime(left: Boolean) {
        try {
            tone?.startTone(if (left) ToneGenerator.TONE_PROP_BEEP else ToneGenerator.TONE_PROP_BEEP2, 250)
        } catch (_: Throwable) {}
    }

    /**
     * 마이크로 듣는다. 마이크가 없는 개발 환경(에뮬레이터를 마이크 없는 PC에서 돌릴 때)에서는
     * 앱 내부 폴더(filesDir)의 stt_test.pcm(16kHz 모노 16비트)이 있으면 그 소리를 SDK의 sendAudio로 보내 인식을 시험한다.
     */
    fun listen() {
        val s = stt
        if (s == null || !sttReady) { say("STT 준비 전이라 듣기를 시작하지 못함"); return }
        try {
            s.request()
            val f = java.io.File(context.filesDir, "stt_test.pcm")
            if (f.exists()) {
                say("STT: 시험 음성 파일을 보냄")
                Thread {
                    try {
                        Thread.sleep(600)
                        val bytes = f.readBytes()
                        var i = 0
                        while (i < bytes.size) {
                            val n = minOf(3200, bytes.size - i)           // 100ms 분량
                            s.sendAudio(bytes.copyOfRange(i, i + n))
                            i += n
                            Thread.sleep(100)
                        }
                        s.completeAudioSend()
                    } catch (e: Throwable) { say("STT 시험 음성 전송 실패: ${e.message}") }
                }.start()
            }
        } catch (e: Throwable) { say("STT 요청 실패: ${e.message}") }
    }

    fun stopListening() { try { stt?.stop() } catch (_: Throwable) {} }

    fun stop() {
        try { tts?.stop(); tts?.release() } catch (_: Throwable) {}
        try { stt?.stop(); stt?.release() } catch (_: Throwable) {}
        tone?.release()
        tts = null; stt = null
    }

    private fun say(m: String) { Log.i(CarLink.TAG, m); main.post { log(m) } }
}
