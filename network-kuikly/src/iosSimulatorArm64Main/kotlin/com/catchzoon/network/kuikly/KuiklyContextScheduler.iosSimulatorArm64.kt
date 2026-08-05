package com.catchzoon.network.kuikly

import com.tencent.kuikly.com_tencent_kuikly_ScheduleContextTask
import kotlinx.cinterop.ExperimentalForeignApi

@OptIn(ExperimentalForeignApi::class)
internal actual fun scheduleKuiklyContextTask(token: String) {
    com_tencent_kuikly_ScheduleContextTask(token, resumeKuiklyContextTask)
}
