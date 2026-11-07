package app.zmby.ui.components

import android.content.Context
import android.graphics.Matrix
import android.view.TextureView
import androidx.annotation.OptIn
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import app.zmby.trailer.TrailerFormat
import app.zmby.log.ZLog
import app.zmby.trailer.TrailerInfo
import app.zmby.trailer.TrailerMedia
import app.zmby.zmby
import kotlinx.coroutines.flow.collectLatest

/*
 * Trailers in the home hero and at the top of the detail pages (trailer design; ui-spec §2.1, §2.2):
 * a 16:9 window at the top right whose left and bottom edges dissolve into the still backdrop, which
 * stays drawn around it, never the whole background. One
 * ExoPlayer per screen, built only when a trailer is found and released whenever the screen pauses
 * (the Zidoo player needs the decoder) or leaves. The video loads under the still backdrop and fades
 * in only once its first frame is decoded, so a slow or failing trailer never shows a black frame.
 * A TextureView, not a SurfaceView: the video sits under the page's scrims, glass and sliding pages,
 * which a SurfaceView (a hole behind the window) cannot do; its cost is one more full-screen texture
 * per video frame, only while a trailer plays.
 */

/** Where a trailer stands, as the screens read it (draw-phase reads for the fade and the progress). */
@Stable
@OptIn(UnstableApi::class)
class TrailerPlayer internal constructor(private val context: Context, private val media: TrailerMedia) {
    /** The title whose trailer is loaded or playing (a hero page's item key, the detail page's work). */
    var key by mutableStateOf<String?>(null)
        private set

    /** From the first decoded frame until it ends, fails or stops: the surface fades in while true. */
    var showing by mutableStateOf(false)
        private set

    /** The key of the trailer that last played to its end (the hero then turns the page). */
    var finished by mutableStateOf<String?>(null)
        private set

    /** Paused and faded out while the page is scrolled away from its top (detail pages). */
    var held by mutableStateOf(false)
        private set

    /** The surface's opacity, animated by [TrailerSurface]. */
    internal val alpha = Animatable(0f)

    private var player: ExoPlayer? = null
    private var view: TrailerTextureView? = null
    private var sound = false

    private val listener = object : Player.Listener {
        override fun onRenderedFirstFrame() {
            if (key == null) return
            showing = true
            if (!held) player?.play()
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (state != Player.STATE_ENDED) return
            showing = false
            finished = key
            // Frees the decoder; the last frame stays on the surface while it fades out.
            player?.stop()
        }

        override fun onPlayerError(error: PlaybackException) {
            // Never the link: only the kind of failure.
            ZLog.i("Trailer", "trailer playback failed: ${error.errorCodeName}")
            showing = false
            player?.stop()
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.width > 0 && videoSize.height > 0) view?.videoAspect = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
        }
    }

    /**
     * Loads [info] for [key] paused under the backdrop; it starts and shows on its first frame.
     * Loading the key already loaded does nothing (also after it finished: no replay).
     */
    fun load(key: String, info: TrailerInfo, sound: Boolean) {
        if (this.key == key) return
        stop()
        this.key = key
        this.sound = sound
        finished = null
        val p = player ?: build().also { player = it }
        p.setAudioAttributes(AUDIO, sound)
        p.volume = if (sound) 1f else 0f
        // Muted: no audio track at all, so nothing is decoded for it and no audio focus is taken.
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, !sound)
            // HLS offers subtitles; a background trailer shows none.
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            // An HLS ladder goes past what a background needs: 1080p at most, and its lighter rung.
            .setMaxVideoSize(1920, 1080)
            .setMaxVideoBitrate(MAX_VIDEO_BITRATE)
            .build()
        view?.let(p::setVideoTextureView)
        p.setMediaSource(mediaSource(info))
        p.playWhenReady = false
        p.prepare()
    }

    /** Stops what plays (the surface fades out) and forgets it. */
    fun stop() {
        key = null
        showing = false
        player?.let {
            it.stop()
            it.clearMediaItems()
        }
    }

    /** Paused and faded out while [hold] (the detail page scrolled down), shown and playing again after. */
    fun hold(hold: Boolean) {
        if (held == hold) return
        held = hold
        val p = player ?: return
        if (hold) p.pause() else if (showing) p.play()
    }

    /** Releases the player and its decoder; a later [load] builds a new one. */
    fun release() {
        stop()
        player?.release()
        player = null
    }

    /** How far the trailer has played, 0..1 (read while drawing the hero's page dot). */
    fun progress(): Float {
        val p = player ?: return 0f
        val d = p.duration
        return if (d <= 0L || d == C.TIME_UNSET) 0f else (p.currentPosition.toFloat() / d).coerceIn(0f, 1f)
    }

    internal fun attach(v: TrailerTextureView) {
        view = v
        player?.setVideoTextureView(v)
    }

    internal fun detach(v: TrailerTextureView) {
        if (view !== v) return
        player?.clearVideoTextureView(v)
        view = null
    }

    /**
     * IMDb's MP4 under its video key and Rotten Tomatoes' HLS segments go through the playback cache;
     * a server's own trailer streams straight from the server with its User-Agent.
     */
    private fun mediaSource(info: TrailerInfo): MediaSource = when (info.format) {
        TrailerFormat.Mp4 -> ProgressiveMediaSource.Factory(media.dataSourceFactory())
            .createMediaSource(MediaItem.Builder().setUri(info.url).setCustomCacheKey(info.cacheKey).setMimeType(MimeTypes.VIDEO_MP4).build())
        TrailerFormat.Hls -> HlsMediaSource.Factory(media.dataSourceFactory())
            .createMediaSource(MediaItem.Builder().setUri(info.url).setMimeType(MimeTypes.APPLICATION_M3U8).build())
        TrailerFormat.Server -> ProgressiveMediaSource.Factory(media.serverDataSourceFactory(info.userAgent))
            .createMediaSource(MediaItem.fromUri(info.url))
    }

    private fun build(): ExoPlayer = ExoPlayer.Builder(context)
        // A trailer is short: a small buffer, and playback starts after 1.5 s of it.
        .setLoadControl(DefaultLoadControl.Builder()
            .setBufferDurationsMs(10_000, 30_000, 1_500, 3_000)
            .setTargetBufferBytes(24 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build())
        .setRenderersFactory(DefaultRenderersFactory(context).setEnableDecoderFallback(true))
        // Start HLS on a high rung (a box on the LAN, usually fast), not the blurry lowest one.
        .setBandwidthMeter(DefaultBandwidthMeter.Builder(context).setInitialBitrateEstimate(INITIAL_BITRATE).build())
        .build()
        .apply {
            repeatMode = Player.REPEAT_MODE_OFF
            addListener(listener)
        }

    private companion object {
        const val MAX_VIDEO_BITRATE = 9_000_000
        const val INITIAL_BITRATE = 8_000_000L
        val AUDIO: AudioAttributes = AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build()
    }
}

/** A [TrailerPlayer] for this screen, released when the screen pauses (before the Zidoo player starts) or leaves. */
@Composable
fun rememberTrailerPlayer(): TrailerPlayer {
    val context = LocalContext.current
    val player = remember { TrailerPlayer(context.applicationContext, context.zmby.trailers.media) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, player) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) player.release() }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            player.release()
        }
    }
    return player
}

/**
 * The trailer window over a full-screen backdrop (trailer design): a 16:9 box two thirds of the
 * screen high at the top right, which on the home page is the hero area. Its left and bottom edges
 * fade out over [EDGE_LEFT] and [EDGE_BOTTOM] of the box, so the video dissolves into the still
 * backdrop drawn under it. Compose it only while [TrailerPlayer.key] is set.
 */
@Composable
fun BoxScope.TrailerWindow(player: TrailerPlayer) {
    TrailerSurface(
        player,
        Modifier
            .align(Alignment.TopEnd)
            .fillMaxHeight(WINDOW_HEIGHT)
            .aspectRatio(16f / 9f, matchHeightConstraintsFirst = true)
            // Its own layer, so the masks below cut only the video, not the backdrop under it.
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithCache {
                val left = Brush.horizontalGradient(0f to Color.Transparent, EDGE_LEFT to Color.Black, endX = size.width)
                val bottom = Brush.verticalGradient((1f - EDGE_BOTTOM) to Color.Black, 1f to Color.Transparent, endY = size.height)
                onDrawWithContent {
                    drawContent()
                    drawRect(left, blendMode = BlendMode.DstIn)
                    drawRect(bottom, blendMode = BlendMode.DstIn)
                }
            },
    )
}

/** The window's height as a fraction of the screen (720 of 1080 design dp, the hero's bottom edge). */
private const val WINDOW_HEIGHT = 2f / 3f
private const val EDGE_LEFT = 0.35f
private const val EDGE_BOTTOM = 0.3f

/**
 * The video, filling its box like a cropped backdrop; transparent until [TrailerPlayer.showing],
 * then fading in (and out again when it ends, stops or is held). Compose it only while [TrailerPlayer.key] is set.
 */
@Composable
private fun TrailerSurface(player: TrailerPlayer, modifier: Modifier = Modifier) {
    LaunchedEffect(player) {
        snapshotFlow { player.showing && !player.held }.collectLatest { on ->
            player.alpha.animateTo(if (on) 1f else 0f, tween(if (on) FADE_IN_MS else FADE_OUT_MS))
        }
    }
    AndroidView(
        factory = { ctx -> TrailerTextureView(ctx).also(player::attach) },
        onRelease = player::detach,
        modifier = modifier.graphicsLayer { alpha = player.alpha.value },
    )
}

private const val FADE_IN_MS = 900
private const val FADE_OUT_MS = 500

/**
 * Scale that makes a video of [videoAspect] cover a [width] × [height] view without distortion
 * (TextureView stretches the picture to the view): the overflowing axis is enlarged.
 */
internal fun coverScale(width: Int, height: Int, videoAspect: Float): Pair<Float, Float> {
    if (width <= 0 || height <= 0 || videoAspect <= 0f) return 1f to 1f
    val viewAspect = width.toFloat() / height
    return if (videoAspect > viewAspect) (videoAspect / viewAspect) to 1f else 1f to (viewAspect / videoAspect)
}

/** A TextureView that crops its video to cover the view. */
internal class TrailerTextureView(context: Context) : TextureView(context) {
    var videoAspect = 16f / 9f
        set(value) {
            if (field == value) return
            field = value
            applyCover()
        }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        applyCover()
    }

    private fun applyCover() {
        val (sx, sy) = coverScale(width, height, videoAspect)
        setTransform(Matrix().apply { setScale(sx, sy, width / 2f, height / 2f) })
    }
}
