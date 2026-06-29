package com.example.ecganalysis

import android.app.AlertDialog
import android.content.DialogInterface
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.ecganalysis.core.data.GlobalPI
import com.example.ecganalysis.textVoice.TextToSpeech
import com.example.ecganalysis.audio.piper.PiperAudioPackage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

import kotlin.math.floor

class RiskReportActivity : ComponentActivity() {
    companion object {
        private const val TAG = "Risk_Report_Activity"
    }

    private lateinit var textToSpeech: TextToSpeech

    // Piper audio package for offline Piper TTS
    private lateinit var piperPackage: PiperAudioPackage

    //NEW PART

    private var currentPlayingTextViewId= -1
    private var currentSpeakButton: ImageButton? = null
    private var isSpeaking = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_risk_report)

        textToSpeech = TextToSpeech(this)

        // Initialize Piper package (non-blocking). Keep native TTS intact.
        piperPackage = PiperAudioPackage(this)
        piperPackage.initialize()

        buildECGReport()
        buildRiskReport()

        val detailReportBtn: Button = findViewById(R.id.nextBtn)
        detailReportBtn.setOnClickListener { detailReportListener() }

        val riskLayout: LinearLayout = findViewById(R.id.riskHeadGrpId)
        riskLayout.setOnClickListener { expandReportListener(
            R.id.riskInfoGrpId, R.id.riskDropdownId
        ) }

        val ecgLayout: LinearLayout = findViewById(R.id.ecgHeadGrpId)
        ecgLayout.setOnClickListener { expandReportListener(
            R.id.ecgInfoGrpId, R.id.ecgDropdownId
        ) }
    }

    private fun buildECGReport() {
        val score = GlobalPI.ecgInfo.getReportScore()
        if (score == -1) {
            Toast.makeText(this@RiskReportActivity, "Incorrect Data Capture\nGenerate Report Again !!!", Toast.LENGTH_LONG).show()
            finish()
        } else if (score >= 2) {
            showReport1()
            showReport2()
            showReport3()
        } else {
            if (checkOtherCVDs()) {
                showReport1()
            } else {
                showReport1()
            }
        }
    }

    private fun showReport1() {
        val str = "Indication: ${GlobalPI.ecgInfo.getDiagnosis()}"
        val reportView = findViewById<TextView>(R.id.report1)

        reportView.text = str
        reportView.visibility = View.VISIBLE
    }

    private fun showReport2() {
        val str = "Mean Heart Rate: ${GlobalPI.ecgInfo.getHR()} bpm\n" +
                "Heart Rate Std: ${GlobalPI.ecgInfo.getHRStd()} bpm"

        val reportView = findViewById<TextView>(R.id.report2)
        reportView.text = str
        reportView.visibility = View.VISIBLE
    }

    private fun showReport3() {
        val str = when (GlobalPI.ecgInfo.pClass) {
            "MI" -> {
                GlobalPI.ecgInfo.getRhythmStats() +
                        "\nST Segment Elevated" +
                        "\nNormal T-Waves"
            }
            "AFL" -> {
                "${GlobalPI.ecgInfo.getRhythmStats()}\n" +
                        "Sawtooth Flutter waves seen"
            }
            else -> {
                GlobalPI.ecgInfo.getRhythmStats() +
                        "\n${GlobalPI.ecgInfo.getRRIntervalStats()}" +
                        "\n${GlobalPI.ecgInfo.getPWaveStats()}"
            }
        }

        val reportView = findViewById<TextView>(R.id.report3)
        reportView.text = str
        reportView.visibility = View.VISIBLE
    }

    private fun checkOtherCVDs(): Boolean {
        val modelProbs: List<Float> = GlobalPI.ecgInfo.modelProbs
        var sumProbsAF = 0F
        var cntProbsAF = 0

        var sumProbsNSR = 0F
        var cntProbsNSR = 0
        for (i in 1..modelProbs.size step 2) {
            if (modelProbs[i - 1] < modelProbs[i]) {
                // AF Case
                sumProbsAF += modelProbs[i - 1]
                cntProbsAF += 1
            } else {
                // NSR Case
                sumProbsNSR += modelProbs[i]
                cntProbsNSR += 1
            }
        }

        if (cntProbsNSR == 0 || cntProbsAF == 0) {
            return false
        }

        val meanAFProbs = sumProbsAF / cntProbsAF
        val meanNSRProbs = sumProbsNSR / cntProbsNSR

        val alpha = if (meanNSRProbs <= meanAFProbs) (meanNSRProbs / meanAFProbs) else (meanAFProbs / meanNSRProbs)
        return alpha >= 0.8
    }

    private fun expandReportListener(reportGrpViewId: Int, expandId: Int) {
        val reportGrpView: LinearLayout = findViewById(reportGrpViewId)
        val expandButton: ImageButton = findViewById(expandId)

        if (reportGrpView.visibility == View.VISIBLE) {
            expandButton.setBackgroundResource(R.drawable.expand_more)
            reportGrpView.visibility = View.GONE
        } else {
            expandButton.setBackgroundResource(R.drawable.expand_less)
            reportGrpView.visibility = View.VISIBLE
        }
    }

    private fun buildRiskReport() {
        buildQRisk()
        buildAscvd()
        if (!GlobalPI.clinicalInfo.isDiabetic())
            buildFramingham()
        if (GlobalPI.ecgInfo.pClass == "AF" || GlobalPI.ecgInfo.pClass == "AFL") {
            buildCha2Ds2()
            buildHasBled()
        }
        if (GlobalPI.isECHOInfoInitialized() && GlobalPI.echoInfo.getLVEF() != -1) {
            buildMaggic()
        }
    }

    private fun buildQRisk() {
        val riskModule = GlobalPI.riskInfo.getQRisk()
        val riskInterpretation = when(riskModule.getRiskStratification()) {
            0 -> "Low Risk."
            1 -> "Moderate Risk."
            else -> "High Risk."
        }
        val riskRange = when(riskModule.getRiskStratification()) {
            0 -> "Less than 10%"
            1 -> "Between 11% and 20%"
            else -> "More than 20%"
        }

        val riskTextInfo: String = when (riskModule.checkIfPossible()) {
            true -> {
                "- 10-yr Score: ${riskModule.getScore()} %\n" +
                        "- Relative Healthy Score: ${riskModule.getHealthyScore()} %\n" +
                        "- Relative Risk: ${riskModule.getRelativeRiskScore()}" +
                        "\n\n" +
                        "Interpretation: $riskInterpretation"
            }
            else -> {
                "Score can not be generated. Need more information.\n\n" + riskModule.getSanityCheckList()
            }
        }

        val expandView: RelativeLayout = findViewById(R.id.qRiskHeadGrpId)
        expandView.setOnClickListener { expandReportListener(
            riskModule.checkIfPossible(), riskModule.getRiskStratification(), riskTextInfo,
            R.id.qRiskReportId, R.id.qRiskGrpId, R.id.qRiskDropdownId
        ) }

        // --- Piper for QRisk speak button ---
        val speakBtn: ImageButton = findViewById(R.id.qRiskSpeakId)

        speakBtn.setOnClickListener {
            val reportView: TextView = findViewById(R.id.qRiskReportId)
            if(isSpeaking && currentSpeakButton == speakBtn && currentPlayingTextViewId == reportView.id){
                piperPackage.stopCurrent()
                speakBtn.alpha=1f
                isSpeaking = false
                currentSpeakButton = null
                currentPlayingTextViewId = -1

                return@setOnClickListener
            }

            if(currentPlayingTextViewId != null && currentSpeakButton !=speakBtn){
                currentSpeakButton?.alpha =1.0f
            }


            val raw = reportView.text.toString().trim()
            if (raw.isEmpty()) return@setOnClickListener

            if(currentPlayingTextViewId == reportView.id){
                piperPackage.stopCurrent()
                speakBtn.alpha = 1f

                currentPlayingTextViewId = -1
                currentSpeakButton = null

                return@setOnClickListener
            }

            stopPreviousPlayback(reportView.id, speakBtn)

            val spokenText = buildQriskSpokenText(raw)

            if (piperPackage.isReady()) {
                currentPlayingTextViewId = reportView.id
                currentSpeakButton =speakBtn

                piperPackage.onStart = {
                    runOnUiThread {
                        currentSpeakButton = speakBtn
                        currentPlayingTextViewId = reportView.id
                        isSpeaking =true
                        speakBtn.alpha =0.5f
                    }
                }
                piperPackage.onDone = {
                    runOnUiThread {
                        speakBtn.alpha = 1f
                        if(currentSpeakButton== speakBtn) {
                            isSpeaking = false
                            currentPlayingTextViewId = -1
                            currentSpeakButton = null
                        }
                    }
                }
                piperPackage.onError = {
                    runOnUiThread {
                        speakBtn.alpha = 1f
                        if(currentSpeakButton== speakBtn) {
                            isSpeaking = false
                            currentPlayingTextViewId = -1
                            currentSpeakButton = null
                        }
                    }
                }

                piperPackage.speak(spokenText, speed = 1.0f)
            } else {
                lifecycleScope.launch(Dispatchers.Default) {
                    textToSpeech.speak(reportView.text.toString(), reportView.id)
                    do {
                        delay(1000)
                    } while (textToSpeech.isBusy())
                }
            }
        }
        // --- end QRisk ---

        val extraInfo = "${riskInterpretation.dropLast(1)} ($riskRange):\n" +
                "You are at a ${floor(riskModule.getScore()).toInt()}% chance of having a " +
                "heart attack / stroke within the next 10 years."

        val infoBtn: ImageButton = findViewById(R.id.qRiskInfoId)
        infoBtn.setOnClickListener { showInfoDialog(
            R.layout.qrisk_info_dialog, riskModule.checkIfPossible(), R.id.qRiskDialogId, extraInfo
        ) }

        val layout: LinearLayout = findViewById(R.id.qRiskLayoutId)
        layout.visibility = View.VISIBLE
    }

    private fun buildFramingham() {
        val riskModule = GlobalPI.riskInfo.getFramingham()
        val riskInterpretation = when(riskModule.getRiskStratification()) {
            0 -> "Low Risk."
            1 -> "Moderate Risk."
            else -> "High Risk."
        }
        val riskRange = when(riskModule.getRiskStratification()) {
            0 -> "Less than 10%"
            1 -> "Between 11% and 20%"
            else -> "More than 20%"
        }

        val riskTextInfo = when (riskModule.checkIfPossible()) {
            true -> {
                "10 yr Score: ${riskModule.getScore()} %" +
                        "\n\n" +
                        "Interpretation: $riskInterpretation"
            }
            else -> {
                "Score cannot be generated. Need more information.\n\n" + riskModule.getSanityCheckList()
            }
        }

        val expandView: RelativeLayout = findViewById(R.id.framinghamHeadGrpId)
        expandView.setOnClickListener { expandReportListener(
            riskModule.checkIfPossible(), riskModule.getRiskStratification(), riskTextInfo,
            R.id.framinghamReportId, R.id.framinghamGrpId, R.id.framinghamDropdownId
        ) }

        // --- Piper for Framingham speak button ---
        val speakBtn: ImageButton = findViewById(R.id.framinghamSpeakId)

        speakBtn.setOnClickListener {
            val reportView: TextView = findViewById(R.id.framinghamReportId)
            if(isSpeaking && currentSpeakButton == speakBtn && currentPlayingTextViewId == reportView.id){
                piperPackage.stopCurrent()
                speakBtn.alpha=1f
                isSpeaking = false
                currentSpeakButton = null
                currentPlayingTextViewId = -1

                return@setOnClickListener
            }

            if(currentPlayingTextViewId != null && currentSpeakButton !=speakBtn){
                currentSpeakButton?.alpha =1.0f
            }

            val raw = reportView.text.toString().trim()
            if (raw.isEmpty()) return@setOnClickListener

            if(currentPlayingTextViewId == reportView.id){
                piperPackage.stopCurrent()
                speakBtn.alpha = 1f

                currentPlayingTextViewId = -1
                currentSpeakButton = null

                return@setOnClickListener
            }

            stopPreviousPlayback(reportView.id, speakBtn)


            val spokenText = "Framingham: $raw"

            if (piperPackage.isReady()) {
                currentPlayingTextViewId = reportView.id
                currentSpeakButton =speakBtn

                piperPackage.onStart = {
                    runOnUiThread {
                        currentSpeakButton = speakBtn
                        currentPlayingTextViewId = reportView.id
                        isSpeaking =true
                        speakBtn.alpha =0.5f
                    }
                }
                piperPackage.onDone = {
                    runOnUiThread {
                        speakBtn.alpha = 1f
                        if(currentSpeakButton== speakBtn) {
                            isSpeaking = false
                            currentPlayingTextViewId = -1
                            currentSpeakButton = null
                        }
                    }
                }
                piperPackage.onError = {
                    runOnUiThread {
                        speakBtn.alpha = 1f
                        if(currentSpeakButton== speakBtn) {
                            isSpeaking = false
                            currentPlayingTextViewId = -1
                            currentSpeakButton = null
                        }
                    }
                }

                piperPackage.speak(spokenText, speed = 1.0f)
            } else {
                lifecycleScope.launch(Dispatchers.Default) {
                    textToSpeech.speak(reportView.text.toString(), reportView.id)
                    do {
                        delay(1000)
                    } while (textToSpeech.isBusy())
                }
            }
        }
        // --- end Framingham ---

        val extraInfo = "${riskInterpretation.dropLast(1)} ($riskRange):\n" +
                "You are at a ${floor(riskModule.getScore()).toInt()}% chance of having a " +
                "heart attack / stroke within the next 10 years."

        val infoBtn: ImageButton = findViewById(R.id.framinghamInfoId)
        infoBtn.setOnClickListener { showInfoDialog(
            R.layout.framingham_info_dialog, riskModule.checkIfPossible(), R.id.framinghamDialogId, extraInfo
        ) }

        val layout: LinearLayout = findViewById(R.id.framinghamLayoutId)
        layout.visibility = View.VISIBLE
    }

    private fun buildAscvd() {
        val riskModule = GlobalPI.riskInfo.getAscvd()
        val riskInterpretation = when(riskModule.getRiskStratification()) {
            0 -> "Low Risk."
            1 -> "Moderate Risk."
            else -> "High Risk."
        }
        val riskRange = when(riskModule.getRiskStratification()) {
            0 -> "Less than 5%"
            1 -> "Between 6% and 15%"
            else -> "More than 15%"
        }

        val riskTextInfo = when (riskModule.checkIfPossible()) {
            true -> {
                "10 yr Score: ${riskModule.getScore()} %" +
                        "\n\n" +
                        "Interpretation: $riskInterpretation"
            }
            else -> {
                "Score cannot be generated. Need more information.\n\n" + riskModule.getSanityCheckList()
            }
        }

        val expandView: RelativeLayout = findViewById(R.id.ascvdHeadGrpId)
        expandView.setOnClickListener { expandReportListener(
            riskModule.checkIfPossible(), riskModule.getRiskStratification(), riskTextInfo,
            R.id.ascvdReportId, R.id.ascvdGrpId, R.id.ascvdDropdownId
        ) }

        // --- Piper for ASCVD speak button ---
        val speakBtn: ImageButton = findViewById(R.id.ascvdSpeakId)

        speakBtn.setOnClickListener {
            val reportView: TextView = findViewById(R.id.ascvdReportId)
            if(isSpeaking && currentSpeakButton == speakBtn && currentPlayingTextViewId == reportView.id){
                piperPackage.stopCurrent()
                speakBtn.alpha=1f
                isSpeaking = false
                currentSpeakButton = null
                currentPlayingTextViewId = -1

                return@setOnClickListener
            }

            if(currentPlayingTextViewId != null && currentSpeakButton !=speakBtn){
                currentSpeakButton?.alpha =1.0f
            }


            val raw = reportView.text.toString().trim()
            if (raw.isEmpty()) return@setOnClickListener

            if(currentPlayingTextViewId == reportView.id){
                piperPackage.stopCurrent()
                speakBtn.alpha = 1f

                currentPlayingTextViewId = -1
                currentSpeakButton = null

                return@setOnClickListener
            }

            stopPreviousPlayback(reportView.id, speakBtn)


            val spokenText = "ASCVD: $raw"

            if (piperPackage.isReady()) {
                currentPlayingTextViewId = reportView.id
                currentSpeakButton =speakBtn

                piperPackage.onStart = {
                    runOnUiThread {
                        currentSpeakButton = speakBtn
                        currentPlayingTextViewId = reportView.id
                        isSpeaking =true
                        speakBtn.alpha =0.5f
                    }
                }
                piperPackage.onDone = {
                    runOnUiThread {
                        speakBtn.alpha = 1f
                        if(currentSpeakButton== speakBtn) {
                            isSpeaking = false
                            currentPlayingTextViewId = -1
                            currentSpeakButton = null
                        }
                    }
                }
                piperPackage.onError = {
                    runOnUiThread {
                        speakBtn.alpha = 1f
                        if(currentSpeakButton== speakBtn) {
                            isSpeaking = false
                            currentPlayingTextViewId = -1
                            currentSpeakButton = null
                        }
                    }
                }

                piperPackage.speak(spokenText, speed = 1.0f)
            } else {
                lifecycleScope.launch(Dispatchers.Default) {
                    textToSpeech.speak(reportView.text.toString(), reportView.id)
                    do {
                        delay(1000)
                    } while (textToSpeech.isBusy())
                }
            }
        }
        // --- end ASCVD ---

        val extraInfo = "${riskInterpretation.dropLast(1)} ($riskRange):\n" +
                "You are at a ${floor(riskModule.getScore()).toInt()}% chance of having a " +
                "major ASCVD event within the next 10 years."

        val infoBtn: ImageButton = findViewById(R.id.ascvdInfoId)
        infoBtn.setOnClickListener { showInfoDialog(
            R.layout.ascvd_info_dialog, riskModule.checkIfPossible(), R.id.ascvdDialogId, extraInfo
        ) }

        val layout: LinearLayout = findViewById(R.id.ascvdLayoutId)
        layout.visibility = View.VISIBLE
    }

    private fun buildCha2Ds2() {
        val riskModule = GlobalPI.riskInfo.getCha2Ds2()
        val riskInterpretation = when(riskModule.getRiskStratification()) {
            0 -> "Low Risk.\nNo Anticoagulant Therapy may be required."
            1 -> "Moderate Risk.\nOral Anticoagulant may be considered."
            else -> "High Risk.\nOral Anticoagulant is recommended."
        }

        val riskTextInfo = when (riskModule.checkIfPossible()) {
            true -> {
                "Score: ${riskModule.getScore()}" +
                        "\n\n" +
                        "Interpretation: $riskInterpretation"
            }
            else -> {
                "Score cannot be generated. Need more information.\n\n" + riskModule.getSanityCheckList()
            }
        }

        val header: TextView = findViewById(R.id.cha2ds2HeadId)
        header.text = getText(R.string.cha2ds2)

        val expandView: RelativeLayout = findViewById(R.id.cha2ds2HeadGrpId)
        expandView.setOnClickListener { expandReportListener(
            riskModule.checkIfPossible(), riskModule.getRiskStratification(), riskTextInfo,
            R.id.cha2ds2ReportId, R.id.cha2ds2GrpId, R.id.cha2ds2DropdownId
        ) }

        // --- Piper for CHA2DS2 speak button ---
        val speakBtn: ImageButton = findViewById(R.id.cha2ds2SpeakId)

        speakBtn.setOnClickListener {
            val reportView: TextView = findViewById(R.id.cha2ds2ReportId)
            if(isSpeaking && currentSpeakButton == speakBtn && currentPlayingTextViewId == reportView.id){
                piperPackage.stopCurrent()
                speakBtn.alpha=1f
                isSpeaking = false
                currentSpeakButton = null
                currentPlayingTextViewId = -1

                return@setOnClickListener
            }

            if(currentPlayingTextViewId != null && currentSpeakButton !=speakBtn){
                currentSpeakButton?.alpha =1.0f
            }


            val raw = reportView.text.toString().trim()
            if (raw.isEmpty()) return@setOnClickListener

            if(currentPlayingTextViewId == reportView.id){
                piperPackage.stopCurrent()
                speakBtn.alpha = 1f

                currentPlayingTextViewId = -1
                currentSpeakButton = null

                return@setOnClickListener
            }

            stopPreviousPlayback(reportView.id, speakBtn)


            val spokenText = "CHA2DS2: $raw"

            if (piperPackage.isReady()) {
                currentPlayingTextViewId = reportView.id
                currentSpeakButton =speakBtn

                piperPackage.onStart = {
                    runOnUiThread {
                        currentSpeakButton = speakBtn
                        currentPlayingTextViewId = reportView.id
                        isSpeaking =true
                        speakBtn.alpha =0.5f
                    }
                }
                piperPackage.onDone = {
                    runOnUiThread {
                        speakBtn.alpha = 1f
                        if(currentSpeakButton== speakBtn) {
                            isSpeaking = false
                            currentPlayingTextViewId = -1
                            currentSpeakButton = null
                        }
                    }
                }
                piperPackage.onError = {
                    runOnUiThread {
                        speakBtn.alpha = 1f
                        if(currentSpeakButton== speakBtn) {
                            isSpeaking = false
                            currentPlayingTextViewId = -1
                            currentSpeakButton = null
                        }
                    }
                }

                piperPackage.speak(spokenText, speed = 1.0f)
            } else {
                lifecycleScope.launch(Dispatchers.Default) {
                    textToSpeech.speak(reportView.text.toString(), reportView.id)
                    do {
                        delay(1000)
                    } while (textToSpeech.isBusy())
                }
            }
        }
        // --- end CHA2DS2 ---

        val extraInfo = when(riskModule.getRiskStratification()) {
            0 -> getText(R.string.cha2ds2LowRisk)
            1 -> getText(R.string.cha2ds2ModerateRisk)
            else -> getText(R.string.cha2ds2HighRisk)
        }

        val infoBtn: ImageButton = findViewById(R.id.cha2ds2InfoId)
        infoBtn.setOnClickListener { showInfoDialog(
            R.layout.cha2ds2_info_dialog, riskModule.checkIfPossible(), R.id.cha2ds2DialogId, extraInfo.toString()
        ) }

        val layout: LinearLayout = findViewById(R.id.cha2ds2LayoutId)
        layout.visibility = View.VISIBLE
    }

    private fun buildHasBled() {
        val riskModule = GlobalPI.riskInfo.getHasBled()
        val riskInterpretation = when(riskModule.getRiskStratification()) {
            0 -> "Low Risk.\nAnticoagulation should be considered."
            1 -> "Moderate Risk.\nAnticoagulation can be considered."
            2 -> "High Risk.\nAlternatives to anticoagulation should be considered."
            else -> "Very High Risk.\nAlternatives to anticoagulation should be considered."
        }

        val riskTextInfo = when (riskModule.checkIfPossible()) {
            true -> {
                "Score: ${riskModule.getScore()}" +
                        "\n\n" +
                        "Interpretation: $riskInterpretation"
            }
            else -> {
                "Score cannot be generated. Need more information.\n\n" + riskModule.getSanityCheckList()
            }
        }

        val expandView: RelativeLayout = findViewById(R.id.hasBledHeadGrpId)
        expandView.setOnClickListener { expandReportListener(
            riskModule.checkIfPossible(), riskModule.getRiskStratification(), riskTextInfo,
            R.id.hasBledReportId, R.id.hasBledGrpId, R.id.hasBledDropdownId
        ) }

        // --- Piper for HAS-BLED speak button ---
        val speakBtn: ImageButton = findViewById(R.id.hasBledSpeakId)

        speakBtn.setOnClickListener {
            val reportView: TextView = findViewById(R.id.hasBledReportId)
            if(isSpeaking && currentSpeakButton == speakBtn && currentPlayingTextViewId == reportView.id){
                piperPackage.stopCurrent()
                speakBtn.alpha=1f
                isSpeaking = false
                currentSpeakButton = null
                currentPlayingTextViewId = -1

                return@setOnClickListener
            }

            if(currentPlayingTextViewId != null && currentSpeakButton !=speakBtn){
                currentSpeakButton?.alpha =1.0f
            }


            val raw = reportView.text.toString().trim()
            if (raw.isEmpty()) return@setOnClickListener

            if(currentPlayingTextViewId == reportView.id){
                piperPackage.stopCurrent()
                speakBtn.alpha = 1f

                currentPlayingTextViewId = -1
                currentSpeakButton = null

                return@setOnClickListener
            }

            stopPreviousPlayback(reportView.id, speakBtn)


            val spokenText = "HAS-BLED: $raw"

            if (piperPackage.isReady()) {
                currentPlayingTextViewId = reportView.id
                currentSpeakButton =speakBtn

                piperPackage.onStart = {
                    runOnUiThread {
                        currentSpeakButton = speakBtn
                        currentPlayingTextViewId = reportView.id
                        isSpeaking =true
                        speakBtn.alpha =0.5f
                    }
                }
                piperPackage.onDone = {
                    runOnUiThread {
                        speakBtn.alpha = 1f
                        if(currentSpeakButton== speakBtn) {
                            isSpeaking = false
                            currentPlayingTextViewId = -1
                            currentSpeakButton = null
                        }
                    }
                }
                piperPackage.onError = {
                    runOnUiThread {
                        speakBtn.alpha = 1f
                        if(currentSpeakButton== speakBtn) {
                            isSpeaking = false
                            currentPlayingTextViewId = -1
                            currentSpeakButton = null
                        }
                    }
                }

                piperPackage.speak(spokenText, speed = 1.0f)
            } else {
                lifecycleScope.launch(Dispatchers.Default) {
                    textToSpeech.speak(reportView.text.toString(), reportView.id)
                    do {
                        delay(1000)
                    } while (textToSpeech.isBusy())
                }
            }
        }
        // --- end HAS-BLED ---

        val extraInfo = when(riskModule.getRiskStratification()) {
            0 -> getText(R.string.hasBledLowRisk)
            1 -> getText(R.string.hasBledModerateRisk)
            2 -> getText(R.string.hasBledHighRisk)
            else -> getText(R.string.hasBledVeryHighRisk)
        }

        val infoBtn: ImageButton = findViewById(R.id.hasBledInfoId)
        infoBtn.setOnClickListener { showInfoDialog(
            R.layout.hasbled_info_dialog, riskModule.checkIfPossible(), R.id.hasBledDialogId, extraInfo.toString()
        ) }

        val layout: LinearLayout = findViewById(R.id.hasBledLayoutId)
        layout.visibility = View.VISIBLE
    }

    private fun buildMaggic() {
        val riskModule = GlobalPI.riskInfo.getMaggic()
        val riskInterpretation = when(riskModule.getRiskStratification()) {
            0 -> "Low Risk."
            1 -> "Moderate Risk."
            else -> "High Risk."
        }

        val riskTextInfo = when (riskModule.checkIfPossible()) {
            true -> {
                "- 1 Yr Risk: ${riskModule.getScore().second} %\n" +
                        "- 3 Yr Risk: ${riskModule.getScore().third} %\n" +
                        "\n" +
                        "Interpretation: $riskInterpretation"
            }
            else -> {
                "Score cannot be generated. Need more information.\n\n" + riskModule.getSanityCheckList()
            }
        }

        val expandView: RelativeLayout = findViewById(R.id.maggicHeadGrpId)
        expandView.setOnClickListener { expandReportListener(
            riskModule.checkIfPossible(), riskModule.getRiskStratification(), riskTextInfo,
            R.id.maggicReportId, R.id.maggicGrpId, R.id.maggicDropdownId
        ) }

        // --- Piper for MAGGIC speak button ---
        val speakBtn: ImageButton = findViewById(R.id.maggicSpeakId)

        speakBtn.setOnClickListener {
            val reportView: TextView = findViewById(R.id.maggicReportId)
            if(isSpeaking && currentSpeakButton == speakBtn && currentPlayingTextViewId == reportView.id){
                piperPackage.stopCurrent()
                speakBtn.alpha=1f
                isSpeaking = false
                currentSpeakButton = null
                currentPlayingTextViewId = -1

                return@setOnClickListener
            }

            if(currentPlayingTextViewId != null && currentSpeakButton !=speakBtn){
                currentSpeakButton?.alpha =1.0f
            }


            val raw = reportView.text.toString().trim()
            if (raw.isEmpty()) return@setOnClickListener

            if(currentPlayingTextViewId == reportView.id){
                piperPackage.stopCurrent()
                speakBtn.alpha = 1f

                currentPlayingTextViewId = -1
                currentSpeakButton = null

                return@setOnClickListener
            }

            stopPreviousPlayback(reportView.id, speakBtn)


            val spokenText = "MAGGIC: $raw"

            if (piperPackage.isReady()) {
                currentPlayingTextViewId = reportView.id
                currentSpeakButton =speakBtn

                piperPackage.onStart = {
                    runOnUiThread {
                        currentSpeakButton = speakBtn
                        currentPlayingTextViewId = reportView.id
                        isSpeaking =true
                        speakBtn.alpha =0.5f
                    }
                }
                piperPackage.onDone = {
                    runOnUiThread {
                        speakBtn.alpha = 1f
                        if(currentSpeakButton== speakBtn) {
                            isSpeaking = false
                            currentPlayingTextViewId = -1
                            currentSpeakButton = null
                        }
                    }
                }
                piperPackage.onError = {
                    runOnUiThread {
                        speakBtn.alpha = 1f
                        if(currentSpeakButton== speakBtn) {
                            isSpeaking = false
                            currentPlayingTextViewId = -1
                            currentSpeakButton = null
                        }
                    }
                }

                piperPackage.speak(spokenText, speed = 1.0f)
            } else {
                lifecycleScope.launch(Dispatchers.Default) {
                    textToSpeech.speak(reportView.text.toString(), reportView.id)
                    do {
                        delay(1000)
                    } while (textToSpeech.isBusy())
                }
            }
        }
        // --- end MAGGIC ---

        val riskRange = when(riskModule.getRiskStratification()) {
            0 -> "1yr risk less than 5 %"
            1 -> "1yr risk between 5 % and 15 %"
            else -> "1yr risk more than 15 %"
        }

        val extraInfo = "${riskInterpretation.dropLast(1)} ($riskRange):\n" +
                "You are at a ${floor(riskModule.getScore().second).toInt()} % chance of having a " +
                "heart attack / stroke within the next 1 years."

        val infoBtn: ImageButton = findViewById(R.id.maggicInfoId)
        infoBtn.setOnClickListener { showInfoDialog(
            R.layout.maggic_info_dialog, riskModule.checkIfPossible(), R.id.maggicDialogId, extraInfo
        ) }

        val layout: LinearLayout = findViewById(R.id.maggicLayoutId)
        layout.visibility = View.VISIBLE
    }

    private fun expandReportListener(isValid: Boolean, riskCode: Int, reportText: String, reportViewId: Int, reportGrpViewId: Int, expandId: Int) {
        val reportGrpView: RelativeLayout = findViewById(reportGrpViewId)
        val expandButton: ImageButton = findViewById(expandId)

        if (reportGrpView.visibility == View.VISIBLE) {
            expandButton.setBackgroundResource(R.drawable.expand_more)
            reportGrpView.visibility = View.GONE
        } else {
            showReport(isValid, riskCode, reportText, reportViewId)
            expandButton.setBackgroundResource(R.drawable.expand_less)
            reportGrpView.visibility = View.VISIBLE
        }
    }

    private fun showReport(isValid: Boolean, riskCode: Int, response: String, textViewId: Int) {
        val textView: TextView = findViewById(textViewId)
        if (!isValid) {
            textView.text = response
            return
        }

        val startIdx = response.lastIndexOf(':') + 2
        var finishIdx = startIdx
        while (finishIdx < response.length && response[finishIdx] != '.')
            finishIdx++

        val riskColorCode: Int = when(riskCode) {
            0 -> R.color.colorLow
            1 -> R.color.colorModerate
            else -> R.color.colorPrimary
        }

        val spannable = SpannableString(response)
        spannable.setSpan(
            ForegroundColorSpan(ContextCompat.getColor(this, riskColorCode)),
            startIdx, finishIdx,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        spannable.setSpan(
            StyleSpan(Typeface.BOLD),
            startIdx, finishIdx,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        textView.text = spannable
    }

    private fun speakReport(reportId: Int) {
        val reportView: TextView = findViewById(reportId)

        lifecycleScope.launch(Dispatchers.Default) {
            textToSpeech.speak(reportView.text.toString(), reportView.id)
            do {
                delay(1000)
            } while (textToSpeech.isBusy())
        }
    }

    //NEW PART

    private fun stopPreviousPlayback(
        reportId: Int,
        speakButton: ImageButton
    ){
        if(currentPlayingTextViewId == -1)
            return

        if(currentPlayingTextViewId == reportId)
            return

        piperPackage.stopCurrent()

        currentSpeakButton?.alpha = 1f

        currentPlayingTextViewId = -1
        currentSpeakButton = null
    }



    // Helper to build a more spoken-friendly QRisk sentence
    private fun buildQriskSpokenText(raw: String): String {
        val cleaned = raw.replace("%", "").trim()
        val value = cleaned.toFloatOrNull()
        return if (value != null) {
            val formatted = String.format("%.1f", value)
            val percentSpoken = formatted.replace(".", " point ")
            val category = when {
                value < 10.0f -> "low"
                value < 20.0f -> "moderate"
                else -> "high"
            }
            "Your QRisk three score is $percentSpoken percent. This is $category risk."
        } else {
            "Your QRisk score is $raw."
        }
    }

    private fun showInfoDialog(dialogId: Int, isValid: Boolean, extraInfoId: Int, extraInfo: String) {
        // Inflate the custom layout for the dialog
        val dialogView = LayoutInflater.from(this).inflate(dialogId, null)

        if (isValid) {
            val finishIdx = extraInfo.indexOf(':')
            val spannable = SpannableString(extraInfo)
            spannable.setSpan(
                StyleSpan(Typeface.BOLD),
                0, finishIdx,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )

            val riskDialogView: TextView = dialogView.findViewById(extraInfoId)
            riskDialogView.text = spannable
        }

        // Create the AlertDialog
        val alertDialog = AlertDialog.Builder(this)
            .setView(dialogView)  // Set the custom view
            .setCancelable(true)   // Dialog is dismissible when tapping outside
            .setPositiveButton("Ok") { dialogInterface: DialogInterface, _: Int -> dialogInterface.cancel() }
            .create()

        // Show the dialog
        alertDialog.show()
    }

    private fun detailReportListener() {
        val intent = Intent(this, TreatmentIntensityActivity::class.java)
        startActivity(intent)
    }

    override fun onStop() {
        textToSpeech.stopSpeaking()
        currentPlayingTextViewId = -1
        currentSpeakButton = null
        super.onStop()
    }

    override fun onDestroy() {
        // release Piper resources
        try {
            currentPlayingTextViewId = -1
            currentSpeakButton = null
            if (::piperPackage.isInitialized) piperPackage.release()
        } catch (e: Exception) {
            // ignore
        }

        textToSpeech.shutdownService()
        super.onDestroy()
    }
}