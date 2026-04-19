package com.autoclicker.app

import android.graphics.Bitmap

/** JNI 绑定到 libNative.so,PP-OCR 管道。 */
object OcrNative {
    init { System.loadLibrary("Native") }

    /** 识别结果:文本、置信度、四角坐标 (x0,y0,x1,y1,x2,y2,x3,y3) */
    class Result(
        @JvmField val text: String,
        @JvmField val score: Float,
        @JvmField val box: IntArray
    ) {
        /** 包围盒最小 x */
        fun minX(): Int = intArrayOf(box[0], box[2], box[4], box[6]).min()
        fun minY(): Int = intArrayOf(box[1], box[3], box[5], box[7]).min()
        fun maxX(): Int = intArrayOf(box[0], box[2], box[4], box[6]).max()
        fun maxY(): Int = intArrayOf(box[1], box[3], box[5], box[7]).max()
        /** 中心点 */
        fun centerX(): Int = (minX() + maxX()) / 2
        fun centerY(): Int = (minY() + maxY()) / 2
    }

    @JvmStatic external fun nativeInit(
        detModelPath: String, clsModelPath: String, recModelPath: String,
        configPath: String, labelPath: String,
        cpuThreadNum: Int, cpuPowerMode: String
    ): Long

    @JvmStatic external fun nativeRelease(ctx: Long): Boolean

    @JvmStatic external fun nativeRunBitmap(ctx: Long, bitmap: Bitmap): Array<Result>?
}
