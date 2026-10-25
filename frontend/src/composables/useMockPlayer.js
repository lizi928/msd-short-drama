import { computed, onBeforeUnmount, ref } from 'vue'

/**
 * 占位播放器：按真实时长推进，不加载 videoUrl。
 *
 * @param durationMs 视频总时长（毫秒）的 ref
 */
export function useMockPlayer(durationMs) {
    const positionMs = ref(0)
    const playing = ref(false)
    const finished = ref(false)
    const started = ref(false)

    const progress = computed(() => {
        const duration = Number(durationMs.value) || 0
        return duration > 0
            ? Math.min(100, Math.max(0, Math.round(positionMs.value / duration * 100)))
            : 0
    })

    let rafId = 0
    let lastTimestamp = 0

    function cancel() {
        if (rafId) {
            cancelAnimationFrame(rafId)
            rafId = 0
        }
        lastTimestamp = 0
    }

    /**
     * 单调时间差推进，避免后台节流导致一次跳太多。
     */
    function tick(timestamp) {
        rafId = 0
        if (!playing.value) {
            return
        }
        const duration = Number(durationMs.value) || 0
        if (duration <= 0) {
            playing.value = false
            return
        }
        if (!lastTimestamp) {
            lastTimestamp = timestamp
        }
        const delta = Math.min(Math.max(timestamp - lastTimestamp, 0), 1000)
        lastTimestamp = timestamp
        positionMs.value = Math.min(duration, positionMs.value + delta)

        if (positionMs.value >= duration * 0.95) {
            finish()
            return
        }
        rafId = requestAnimationFrame(tick)
    }

    function play() {
        const duration = Number(durationMs.value) || 0
        if (playing.value || finished.value || duration <= 0) {
            return
        }
        playing.value = true
        started.value = true
        lastTimestamp = 0
        cancel()
        rafId = requestAnimationFrame(tick)
    }

    function pause() {
        if (!playing.value) {
            return
        }
        playing.value = false
        cancel()
    }

    function toggle() {
        if (playing.value) {
            pause()
        } else {
            play()
        }
    }

    function finish() {
        const duration = Number(durationMs.value) || 0
        playing.value = false
        cancel()
        positionMs.value = duration
        finished.value = true
    }

    function seek(value) {
        const duration = Number(durationMs.value) || 0
        if (duration <= 0 || finished.value) {
            return
        }
        positionMs.value = Math.min(duration, Math.max(0, Math.round(Number(value) || 0)))
        if (positionMs.value >= duration * 0.95) {
            finish()
        }
    }

    /**
     * 重播只重置本地画面；完成后上报器已锁定，不会再写非完成状态。
     */
    function restart() {
        const duration = Number(durationMs.value) || 0
        if (duration <= 0) {
            return
        }
        finished.value = false
        started.value = true
        positionMs.value = 0
        play()
    }

    /**
     * 加载完成后重新初始化位置。
     */
    function reset(startMs = 0) {
        pause()
        const duration = Number(durationMs.value) || 0
        finished.value = false
        started.value = false
        positionMs.value = Math.min(duration, Math.max(0, Math.round(Number(startMs) || 0)))
    }

    onBeforeUnmount(cancel)

    return {
        positionMs,
        playing,
        finished,
        started,
        progress,
        play,
        pause,
        toggle,
        seek,
        finish,
        restart,
        reset
    }
}
