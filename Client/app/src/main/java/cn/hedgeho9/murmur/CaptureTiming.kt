/** 记录本次拍照到照片首次绘制的耗时，不记录图片内容、路径或历史拍摄数据。 */
package cn.hedgeho9.murmur

/** 只追踪最近一次拍照；首次绘制后清除计时，重组和重新打开图片不重复上报。 */
object CaptureTiming {
    private var pending: Pair<String, Long>? = null

    /** 将完成拍摄的本地图片与按下按钮的单调时钟关联。 */
    @Synchronized
    fun track(path: String, start: Long) {
        pending = path to start
    }

    /** 在真实照片解码成功并执行绘制时记录耗时；不等同于显示面板实际呈现时间。 */
    @Synchronized
    fun firstDraw(path: String) {
        val capture = pending?.takeIf { it.first == path } ?: return
        pending = null
        android.util.Log.d(
            "MurmurCapture",
            "firstDrawMs=${android.os.SystemClock.elapsedRealtime()-capture.second}",
        )
    }
}
