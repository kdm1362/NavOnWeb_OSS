/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.core

enum class VehicleState {
    UNKNOWN,
    BENCH_STATIONARY,
    VERIFIED_PARKED,
    MOVING,
}
data class SafetyDecision(
    val allowed: Boolean,
    val reason: String,
)

/**
 * Deliberately fail-closed. The upstream OpenAuto SensorService reports a fixed
 * UNRESTRICTED value; this Android port does not inherit that behaviour.
 */
class SafetyGate {
    fun canStart(vehicleState: VehicleState): SafetyDecision = when (vehicleState) {
        VehicleState.BENCH_STATIONARY,
        VehicleState.VERIFIED_PARKED,
        -> SafetyDecision(true, "정차 상태가 확인되었습니다.")

        VehicleState.UNKNOWN -> SafetyDecision(false, "차량 상태를 확인할 수 없어 시작하지 않습니다.")
        VehicleState.MOVING -> SafetyDecision(false, "이동 상태에서는 세션을 시작할 수 없습니다.")
    }

    fun canSendInput(vehicleState: VehicleState): SafetyDecision = when (vehicleState) {
        VehicleState.BENCH_STATIONARY,
        VehicleState.VERIFIED_PARKED,
        -> SafetyDecision(true, "입력 허용")

        VehicleState.UNKNOWN -> SafetyDecision(false, "차량 상태 미확인으로 입력을 차단했습니다.")
        VehicleState.MOVING -> SafetyDecision(false, "이동 상태로 입력을 차단했습니다.")
    }
}
