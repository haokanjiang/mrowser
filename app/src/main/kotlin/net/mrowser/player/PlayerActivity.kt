package net.mrowser.player

import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.TypefaceSpan
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.DefaultTimeBar
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import androidx.media3.ui.TrackSelectionDialogBuilder
import net.mrowser.R
import net.mrowser.data.JsonSettingsStore
import net.mrowser.data.SeekSpeed
import net.mrowser.data.Settings
import net.mrowser.data.SettingsRepository
import net.mrowser.stream.MediaMimeType
import net.mrowser.stream.PlaybackRequest
import java.io.File
import kotlin.math.abs

@OptIn(UnstableApi::class)
class PlayerActivity : Activity() {

    private var player: ExoPlayer? = null
    private var subtitleController: SubtitleSyncController? = null
    private var playerView: PlayerView? = null
    private var streamUrl: String? = null
    private var timeBar: View? = null

    // Seek-key gesture state. SeekGesture decides, SeekPreview computes; this owns the timing,
    // as CursorLayout does for the BACK hold. `seekDown` is the keycode of the key being held,
    // so a press on the other direction mid-hold cannot be mistaken for a repeat of this one.
    private val seekHandler = Handler(Looper.getMainLooper())
    private var seekDown = 0
    private var seekHolding = false
    private var holdForward = false
    private var lastControlsRefreshAt = 0L
    private var holdStartedAt = 0L

    /** Set while a scrub pauses playback, so the release can put it back. */
    private var playWhenReadyBeforeHold = true

    /** Whether the OK press in progress is a play/pause rather than a button activation. */
    private var okIsOurs = false

    // The same store the browser uses. Display-mode toggles are read when a stream's tracks
    // arrive; the seek settings are read at every press and are also writable from the gear.
    private val settings: SettingsRepository by lazy { JsonSettingsStore(File(filesDir, "settings.json")) }

    // Display-mode matching. The matcher runs once per stream (this activity is one stream);
    // the window's own preference before that is what onStop puts back, and onStart re-asks.
    private var displayModeMatched = false
    private var originalModeId = 0
    private var requestedModeId = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.player_activity)
        val playerView = findViewById<PlayerView>(R.id.playerView)
        this.playerView = playerView
        timeBar = playerView.findViewById(androidx.media3.ui.R.id.exo_progress)

        val json = intent.getStringExtra(EXTRA_REQUEST) ?: run { finish(); return }
        val request = PlaybackRequest.fromJson(json)
        streamUrl = request.url

        val trackSelector = DefaultTrackSelector(this)
        // DefaultMediaSourceFactory builds the source the request's MIME type asks for — HLS for
        // an .m3u8, DASH for an .mpd, progressive for a plain file. The player carries
        // no text track — side-loaded subtitles are rendered by SubtitleSyncController, not merged here.
        // Cookie is re-resolved per request host (via CookieManager) instead of being sent
        // blanket on every request: a session cookie scoped to the stream host must never
        // ride along to a different CDN/key/redirect host (cross-host session-cookie leak).
        val baseHeaders = request.headers.filterKeys { !it.equals("Cookie", ignoreCase = true) }
        val httpFactory = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(baseHeaders)
            .setAllowCrossProtocolRedirects(true)
        val cookieManager = CookieManager.getInstance()
        val dataSourceFactory = ResolvingDataSource.Factory(httpFactory) { dataSpec ->
            val cookie = cookieManager.getCookie(dataSpec.uri.toString())
            if (cookie.isNullOrEmpty()) dataSpec
            else dataSpec.withAdditionalHeaders(mapOf("Cookie" to cookie))
        }
        val exo = ExoPlayer.Builder(this)
            .setTrackSelector(trackSelector)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build()
        player = exo
        playerView.player = exo

        // Replace the built-in control-bar gear's menu with ours, so video Quality sits
        // alongside Audio and Speed in the same gear. Re-applied whenever the controls
        // re-appear (the player can reset its own listeners).
        // Subtitle on/off + track selection is NOT done via media3's CC button: with no player
        // text track, media3 keeps re-disabling/greying it (its updateAll() wins the ordering),
        // so it's hidden (show_subtitle_button=false) and we use our own button inside the sync box.
        val syncBox = findViewById<View>(R.id.subSyncBox)
        val hasSubtitles = request.subtitles.isNotEmpty()
        val installControls = {
            playerView.findViewById<View?>(androidx.media3.ui.R.id.exo_settings)?.setOnClickListener {
                player?.let { showSettings(it) }
            }
        }
        playerView.setControllerVisibilityListener(
            PlayerView.ControllerVisibilityListener { visibility ->
                installControls()
                syncBox.visibility = if (hasSubtitles) visibility else View.GONE
                // media3 seats first focus on the play/pause button, which this layout drops, so
                // nothing would hold focus and the arrow keys would do nothing. Post it: the
                // controls are mid-animation here and cannot take focus yet.
                if (visibility == View.VISIBLE) playerView.post { focusTimeBar() }
            }
        )
        playerView.post { installControls() }

        // Pick up where this stream was left (BACK to the browser, then Play synced). With nothing
        // saved, no start position at all: an explicit 0 would pin a live stream to the start of
        // its window instead of the live edge.
        val startMs = resumePositions.startFor(request.url)
        if (startMs > 0) exo.setMediaItem(buildMediaItem(request), startMs)
        else exo.setMediaItem(buildMediaItem(request))
        originalModeId = window.attributes.preferredDisplayModeId
        exo.addListener(object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) {
                matchDisplayMode(settle = false)
            }

            // First decoded frame: the last chance for a frame rate, so settle with what we have.
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                matchDisplayMode(settle = true)
            }

            override fun onPlayerError(error: PlaybackException) {
                Toast.makeText(this@PlayerActivity, "Stream error — back to web player", Toast.LENGTH_LONG).show()
                finish()
            }

            // Played to the end: the next open starts over.
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) resumePositions.clear(request.url)
            }

            // TV ignores D-pad/touch during playback, so without this the system
            // sleep timeout fires mid-movie. Hold the wake lock only while actually
            // playing — a paused stream should still let the screen sleep.
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
        })
        exo.prepare()
        val subtitleView = playerView.findViewById<SubtitleView>(androidx.media3.ui.R.id.exo_subtitles)
        val syncValue = findViewById<TextView>(R.id.subSyncValue)
        showDelay(syncValue, 0L)
        val controller = SubtitleSyncController(exo, subtitleView, request.subtitles, request.headers) { off ->
            showDelay(syncValue, off)
        }
        subtitleController = controller
        findViewById<View>(R.id.subSyncSubs).setOnClickListener { showSubtitlePicker() }
        findViewById<View>(R.id.subSyncMinus).setOnClickListener { controller.adjust(-STEP_MS) }
        findViewById<View>(R.id.subSyncPlus).setOnClickListener { controller.adjust(STEP_MS) }
        controller.start()
        updateSubsButton()
        exo.playWhenReady = true
    }

    /**
     * Take the seek keys and OK before the view tree sees them.
     *
     * `PlayerView.dispatchKeyEvent` answers the first D-pad press by revealing the controls and
     * nothing else, so stock media3 needs three presses to seek and does not pause on OK at all.
     * An Activity's dispatch runs first, which is also what makes "a short press shows no
     * controls" free: PlayerView never receives the key, so its reveal branch never runs.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (handleSeekKey(event, forward = code == KeyEvent.KEYCODE_DPAD_RIGHT)) return true
        } else if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
            // Ownership is decided on the press, not the release: DPAD_CENTER is one of
            // PlayerView's "isDpadKey", so letting the press through would reveal the controls,
            // and by the release they would be up — OK would never pause, only reveal.
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (event.repeatCount == 0) okIsOurs = playerOwnsKey()
                if (okIsOurs) return true // Auto-repeats included: a held OK must not re-toggle.
            } else if (event.action == KeyEvent.ACTION_UP && okIsOurs) {
                okIsOurs = false
                togglePlayback()
                return true
            }
        }
        // The media keys are media3's: dispatchMediaKeyEvent already acts on ACTION_DOWN and
        // separates them properly — PLAY plays, PAUSE pauses, PLAY_PAUSE toggles (through
        // Util.handlePlayPauseButtonAction, which also accounts for playback suppression, as
        // scrubbing mode causes). Handling them here as well toggled twice per press — once on
        // its ACTION_DOWN under media3, once on our ACTION_UP — and the two cancelled out.
        return super.dispatchKeyEvent(event)
    }

    /** True when this press is ours; false leaves it to normal focus movement. */
    private fun handleSeekKey(event: KeyEvent, forward: Boolean): Boolean {
        val p = player ?: return false
        val sameKeyDown = seekDown == event.keyCode && seekDown != 0

        return when (SeekGesture.decide(event.action, sameKeyDown, seekHolding)) {
            SeekGesture.Decision.ArmHold -> {
                // The focus gate applies to starting only — see SeekGesture.ownsKey.
                if (!playerOwnsKey()) {
                    false
                } else {
                    // The other direction was held: end that gesture (committing its preview)
                    // and start this one.
                    if (seekDown != 0) endHold()
                    seekDown = event.keyCode
                    holdForward = forward
                    seekHandler.postDelayed(beginHold, SeekGesture.HOLD_MS)
                    true
                }
            }
            SeekGesture.Decision.Step -> {
                stopHoldTimers()
                seekDown = 0
                val s = settings.get()
                val amount = (if (forward) s.seekForwardSec else s.seekBackSec) * 1_000L
                // One step on the bar's preview, which accumulates across presses. The commit is
                // re-armed rather than immediate, so a run of presses costs one seek between
                // them instead of one each that the next discards.
                scrubBy(if (forward) amount else -amount)
                seekHandler.removeCallbacks(commitScrubAction)
                seekHandler.postDelayed(commitScrubAction, SeekGesture.COMMIT_DELAY_MS)
                true
            }
            SeekGesture.Decision.EndHold -> {
                endHold()
                true
            }
            // An auto-repeat while held (the vendor's repeat rate would otherwise fire a burst
            // of seeks), or a release we are not tracking.
            SeekGesture.Decision.Ignore -> sameKeyDown || seekDown != 0
        }
    }

    /**
     * The hold threshold passed: show the controls, pause, and start scrubbing the preview.
     *
     * Playback is paused rather than left running under media3's scrubbing mode. Even with that
     * mode's optimisations, a seek per tick asks the stream to locate and decode a segment 25
     * times a second, which no adaptive stream keeps up with — the picture stalled and the
     * scrubber crawled with it. Paused, the frame simply holds while the bar moves freely, and
     * the stream is asked for exactly one position on release. This is what blbl does.
     */
    private val beginHold = Runnable {
        val p = player ?: return@Runnable
        val duration = p.duration
        // No timeline (live, or not loaded yet): there is nothing to scrub across, so leave the
        // gesture as a step — its preview is already accumulating.
        if (SeekPreview.holdStepMs(duration, SeekGesture.TICK_MS, settings.get().seekSpeed.traverseMs) == 0L) {
            return@Runnable
        }
        seekHolding = true
        holdStartedAt = System.currentTimeMillis()
        lastControlsRefreshAt = 0L
        playWhenReadyBeforeHold = p.playWhenReady
        p.playWhenReady = false
        playerView?.showController()
        seekHandler.post(scrubTick)
    }

    /** Advances the bar's preview only — the player is told once, on release. */
    private val scrubTick = object : Runnable {
        override fun run() {
            val p = player ?: return
            val step = SeekPreview.holdStepMs(
                p.duration, SeekGesture.TICK_MS, settings.get().seekSpeed.traverseMs
            )
            scrubBy(if (holdForward) step else -step)
            // The hide timer is driven by input PlayerView receives, and it never sees these
            // keys — so without this the controls would vanish mid-scrub past the 3s timeout.
            // showController() while fully visible only resets that timer (its uxState is
            // already ALL_VISIBLE), so it neither replays the animation nor moves focus.
            val held = System.currentTimeMillis() - holdStartedAt
            if (held - lastControlsRefreshAt >= CONTROLS_REFRESH_MS) {
                lastControlsRefreshAt = held
                playerView?.showController()
            }
            seekHandler.postDelayed(this, SeekGesture.TICK_MS)
        }
    }

    /** Ends a hold: stop ticking, commit the bar's preview, and restore playback. */
    private fun endHold() {
        stopHoldTimers()
        seekDown = 0
        val wasScrubbing = seekHolding
        seekHolding = false
        commitScrub()
        if (wasScrubbing) player?.playWhenReady = playWhenReadyBeforeHold
    }

    private fun stopHoldTimers() {
        seekHandler.removeCallbacks(beginHold)
        seekHandler.removeCallbacks(scrubTick)
        seekHandler.removeCallbacks(commitScrubAction)
    }

    private val commitScrubAction = Runnable { commitScrub() }

    /**
     * Move the time bar's **own** preview by [deltaMs], without telling the player.
     *
     * `DefaultTimeBar` already implements exactly the model this needs: a key press enters
     * `scrubbing` and moves `scrubPosition`, `update()` draws `scrubbing ? scrubPosition :
     * position`, and `PlayerControlView`'s `onScrubStop` listener performs the single seek — on
     * `DPAD_CENTER`, or 1s after the last press. So the preview, its accumulation across presses
     * and its commit are all media3's, through public API, with no second copy of the state here.
     *
     * Two things had to be supplied. The bar's own increment is `duration / 20` — **5m09s per
     * press on a 1h43m film** — so `setKeyTimeIncrement` is set to this delta first. And its
     * `onKeyDown` is the only public way into `scrubbing`, so the step is applied as a
     * synthesised press. Writing `setPosition` instead does not work: `updateProgress` calls it
     * unconditionally from its own polling loop (it consults `scrubbing` only for the position
     * *label*) and keeps polling while paused, so a preview written that way is pulled back to
     * the player's position within a frame.
     *
     * Per-tick seeking is avoided by leaving `time_bar_scrubbing_enabled` off: with it on,
     * `onScrubMove` seeks on **every** move, which is the stall this redesign is fixing.
     */
    private fun scrubBy(deltaMs: Long) {
        val bar = timeBar as? DefaultTimeBar ?: return
        if (deltaMs == 0L) return
        bar.setKeyTimeIncrement(abs(deltaMs))
        val code = if (deltaMs > 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
        bar.onKeyDown(code, KeyEvent(KeyEvent.ACTION_DOWN, code))
    }

    /**
     * Commit the bar's preview now, rather than waiting out its 1s idle timer.
     *
     * `DPAD_CENTER` is `DefaultTimeBar`'s public "commit the scrub" input, and the key never
     * reaches the bar on its own — OK is play/pause here. A press with no scrub in progress
     * does nothing, so this is safe to call unconditionally.
     */
    private fun commitScrub() {
        val bar = timeBar as? DefaultTimeBar ?: return
        val code = KeyEvent.KEYCODE_DPAD_CENTER
        bar.onKeyDown(code, KeyEvent(KeyEvent.ACTION_DOWN, code))
    }

    private fun controlsVisible(): Boolean = playerView?.isControllerFullyVisible == true

    /** Whether this key is the player's rather than a focused control's — see SeekGesture. */
    private fun playerOwnsKey(): Boolean =
        SeekGesture.ownsKey(controlsVisible(), timeBar?.isFocused == true)

    /**
     * Seat first focus on the time bar when the controls appear.
     *
     * media3 would normally seat it on the play/pause button, but this layout has none, and
     * `requestPlayPauseFocus` does nothing when that view is absent — so the framework's own
     * focus search decides, and it picks the gear. The bar must have it instead: it is what
     * left/right act on, and the gear is a destination reached with up.
     *
     * Deliberately not guarded on `currentFocus == null`: the stray focus this corrects *is* a
     * real view (the gear), so a null check would never fire. It is guarded on the controls
     * having just become visible — the only moment media3 seats focus — so the gear keeps focus
     * once the user moves there, and a user who is already on the bar sees no change.
     */
    private fun focusTimeBar() {
        val bar = timeBar ?: return
        if (!bar.isFocused) bar.requestFocus()
    }

    private fun togglePlayback() {
        val p = player ?: return
        // Pausing makes media3 show the controls indefinitely (shouldShowControllerIndefinitely
        // reads playWhenReady), and resuming hands them back to the show timeout. So pause
        // doubles as "let me see where I am, reach the gear, nudge the subtitles".
        p.playWhenReady = !p.playWhenReady
    }

    /**
     * Ask the display for the mode that fits the stream (DisplayModeMatcher decides), once its
     * video tracks are known. All the selected renditions are passed in, not just the one
     * playing: adaptive HLS starts on a small variant and climbs. A manifest without FRAME-RATE
     * leaves the tracks' rate unknown, so with rate matching on that waits for the decoder's
     * format at the first frame ([settle]) rather than giving up early. `preferredDisplayModeId` is
     * API 23 and switches resolution too, unlike media3's frame-rate strategy (seamless only,
     * API 30+). Most TVs blank for a moment on a switch, which is why both toggles are opt-in.
     */
    private fun matchDisplayMode(settle: Boolean) {
        if (displayModeMatched) return
        val p = player ?: return
        val formats = p.currentTracks.groups
            .filter { it.type == C.TRACK_TYPE_VIDEO && it.isSelected }
            .flatMap { g -> (0 until g.length).filter { g.isTrackSelected(it) }.map { g.getTrackFormat(it) } }
        val renditions = (formats + listOfNotNull(p.videoFormat))
            .map { DisplayModeMatcher.Video(it.width, it.height, it.frameRate) }
        val video = DisplayModeMatcher.target(renditions) ?: return
        val s = settings.get()
        if (!settle && s.matchFrameRate && video.fps == null) return
        displayModeMatched = true
        if (!s.matchFrameRate && !s.matchResolution) return

        @Suppress("DEPRECATION") // Activity.display is API 30; minSdk is 23
        val display = windowManager.defaultDisplay
        val modes = display.supportedModes.map { it.toMatcherMode() }
        val current = display.mode.toMatcherMode()
        val id = DisplayModeMatcher.pick(
            modes, current, video.fps, video.width, video.height, s.matchFrameRate, s.matchResolution
        )
        Log.d(TAG, "display modes=$modes current=$current video=$video -> ${id ?: "keep"}")
        if (id == null) return
        requestedModeId = id
        setPreferredDisplayMode(id)
    }

    private fun android.view.Display.Mode.toMatcherMode() =
        DisplayModeMatcher.Mode(modeId, physicalWidth, physicalHeight, refreshRate)

    private fun setPreferredDisplayMode(id: Int) {
        val attrs = window.attributes
        if (attrs.preferredDisplayModeId == id) return
        attrs.preferredDisplayModeId = id
        window.attributes = attrs
    }

    /**
     * The gear's menu. The seek rows are here as well as in the browser's Settings overlay
     * because this is where a wrong step length is noticed — mid-film, with the remote in hand.
     * Both write the same `settings.json`, and every seek reads it at use time, so a change
     * applies to the very next press.
     */
    private fun showSettings(p: ExoPlayer) {
        val s = settings.get()
        val items = arrayOf(
            getString(R.string.track_video),
            getString(R.string.track_audio),
            getString(R.string.speed),
            getString(R.string.seek_menu_forward, s.seekForwardSec),
            getString(R.string.seek_menu_back, s.seekBackSec),
            getString(R.string.seek_menu_speed, getString(seekSpeedLabelRes(s.seekSpeed)))
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.settings)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> TrackSelectionDialogBuilder(this, items[0], p, C.TRACK_TYPE_VIDEO).build().show()
                    1 -> TrackSelectionDialogBuilder(this, items[1], p, C.TRACK_TYPE_AUDIO).build().show()
                    2 -> showSpeed(p)
                    3 -> showSeekAmount(forward = true)
                    4 -> showSeekAmount(forward = false)
                    5 -> showSeekSpeed()
                }
            }
            .show()
    }

    /** 5s…60s in 5s steps, the current value carrying the checked dot. */
    private fun showSeekAmount(forward: Boolean) {
        val options = (Settings.SEEK_MIN_SEC..Settings.SEEK_MAX_SEC step Settings.SEEK_STEP_SEC).toList()
        val labels = options.map { getString(R.string.seek_seconds, it) }.toTypedArray()
        val current = settings.get().let { if (forward) it.seekForwardSec else it.seekBackSec }
        AlertDialog.Builder(this)
            .setTitle(if (forward) R.string.seek_forward_title else R.string.seek_back_title)
            .setSingleChoiceItems(labels, options.indexOf(current)) { dialog, which ->
                val s = settings.get()
                settings.update(
                    if (forward) s.copy(seekForwardSec = options[which])
                    else s.copy(seekBackSec = options[which])
                )
                dialog.dismiss()
            }
            .show()
    }

    private fun showSeekSpeed() {
        val options = listOf(SeekSpeed.GENTLE, SeekSpeed.STANDARD, SeekSpeed.FAST)
        val labels = options.map { getString(seekSpeedLabelRes(it)) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.seek_speed_title)
            .setSingleChoiceItems(labels, options.indexOf(settings.get().seekSpeed)) { dialog, which ->
                settings.update(settings.get().copy(seekSpeed = options[which]))
                dialog.dismiss()
            }
            .show()
    }

    private fun seekSpeedLabelRes(s: SeekSpeed): Int = when (s) {
        SeekSpeed.GENTLE -> R.string.seek_speed_gentle
        SeekSpeed.STANDARD -> R.string.seek_speed_standard
        SeekSpeed.FAST -> R.string.seek_speed_fast
    }

    private fun showSubtitlePicker() {
        val c = subtitleController ?: return
        val labels = (listOf(getString(R.string.subtitle_off)) + c.trackLabels()).toTypedArray()
        // Single-choice: the showing track (or Off) carries the checked dot and takes first focus.
        AlertDialog.Builder(this)
            .setTitle(R.string.track_subtitles)
            .setSingleChoiceItems(labels, c.selected() + 1) { dialog, which ->
                c.select(which - 1) // entry 0 = Off -> index -1
                updateSubsButton()
                dialog.dismiss()
            }
            .show()
    }

    // Our own subtitle button (in the sync box) — the CC badge is accent when a track is showing
    // (on the white focus disc too), light when off and dark on the disc.
    private fun updateSubsButton() {
        val on = subtitleController?.isShowing() == true
        findViewById<ImageView>(R.id.subSyncSubs).imageTintList =
            if (on) ColorStateList.valueOf(getColor(R.color.accent)) else getColorStateList(R.color.bar_icon_tint)
    }

    /** "Subtitle delay +0.5s": the label in the box's text colour, the number white and medium. */
    private fun showDelay(view: TextView, offsetMs: Long) {
        val label = getString(R.string.sub_sync_label)
        // A true minus sign: as wide as "+" in tabular figures, so the box doesn't shift.
        val number = getString(R.string.sub_sync_value, offsetMs / 1000.0).replace('-', '\u2212')
        view.text = SpannableStringBuilder(label).append(' ').append(
            number,
            ForegroundColorSpan(getColor(R.color.on_surface)),
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        ).apply {
            setSpan(TypefaceSpan("sans-serif-medium"), length - number.length, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun showSpeed(p: ExoPlayer) {
        val speeds = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
        val labels = speeds.map { "${it}x" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.speed)
            .setSingleChoiceItems(labels, speeds.indexOfFirst { it == p.playbackParameters.speed }) { dialog, w ->
                p.setPlaybackSpeed(speeds[w])
                dialog.dismiss()
            }
            .show()
    }

    private fun buildMediaItem(request: PlaybackRequest): MediaItem =
        MediaItem.Builder()
            .setUri(request.url)
            // Null for a progressive file: the factory reads the container itself, and a query
            // string can hide the extension it would otherwise guess a manifest from.
            .setMimeType(MediaMimeType.of(request.url))
            .build()

    override fun onStart() {
        super.onStart()
        subtitleController?.resume()
        if (requestedModeId != 0) setPreferredDisplayMode(requestedModeId)
    }

    // media3's PlayerView does not consume BACK, so the press that was meant to dismiss the
    // controls would close the player. Hide them first; exit only when they are already hidden.
    // The settings and subtitle dialogs take BACK themselves and never get here.
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        val view = playerView
        if (view != null && view.isControllerFullyVisible) {
            view.hideController()
            return
        }
        super.onBackPressed()
    }

    override fun onStop() {
        // Before savePosition: this commits any preview still in flight, so what gets recorded
        // is where the user left the gesture rather than where playback happened to be.
        endHold()
        okIsOurs = false // A press whose release went to another window must not toggle later.
        savePosition()
        super.onStop()
        setPreferredDisplayMode(originalModeId)
        player?.playWhenReady = false
        subtitleController?.stop()
    }

    private fun savePosition() {
        val p = player ?: return
        val url = streamUrl ?: return
        // No timeline yet (left while still loading, or failed before it loaded): nothing is
        // known about this stream, so keep whatever was saved rather than forget it.
        if (p.duration == C.TIME_UNSET) return
        resumePositions.record(url, p.currentPosition, p.duration, p.isCurrentMediaItemLive)
    }

    override fun onDestroy() {
        super.onDestroy()
        endHold()
        setPreferredDisplayMode(originalModeId)
        subtitleController?.stop()
        subtitleController = null
        player?.release()
        player = null
        playerView = null
        timeBar = null
    }

    companion object {
        const val EXTRA_REQUEST = "mrowser.request"
        private const val STEP_MS = 500L
        private const val TAG = "PlayerActivity"

        /** How often a running scrub re-asserts the controls against their 3s hide timeout. */
        private const val CONTROLS_REFRESH_MS = 1_000L

        // Process-wide, so it outlives each PlayerActivity: BACK finishes the player, and the
        // next Play synced starts a new one.
        private val resumePositions = ResumePositions()
    }
}
