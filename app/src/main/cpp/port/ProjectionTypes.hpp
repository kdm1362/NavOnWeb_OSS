/*
 * Android adaptation of OpenAuto projection interfaces.
 * Copyright (C) 2018 f1x.studio (Michal Szwaj)
 * Modifications Copyright (C) 2026 NavOnWeb contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
#pragma once

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdint>

namespace navonweb::openauto_port {

enum class TouchPhase : std::uint8_t {
    Down,
    Move,
    Up,
    Cancel,
};

struct NormalizedTouch {
    TouchPhase phase;
    double x;
    double y;
    std::int32_t pointer_id;
    std::int64_t timestamp_nanos;
};

struct TouchEvent {
    TouchPhase phase;
    std::uint32_t x;
    std::uint32_t y;
    std::int32_t pointer_id;
    std::int64_t timestamp_nanos;
};

struct Viewport {
    std::uint32_t width;
    std::uint32_t height;
};

inline TouchEvent map_touch(const NormalizedTouch& touch, const Viewport& viewport) {
    const auto normalized_x = std::isfinite(touch.x) ? std::clamp(touch.x, 0.0, 1.0) : 0.0;
    const auto normalized_y = std::isfinite(touch.y) ? std::clamp(touch.y, 0.0, 1.0) : 0.0;
    return {
        touch.phase,
        static_cast<std::uint32_t>(normalized_x * static_cast<double>(viewport.width - 1U) + 0.5),
        static_cast<std::uint32_t>(normalized_y * static_cast<double>(viewport.height - 1U) + 0.5),
        std::max<std::int32_t>(0, touch.pointer_id),
        touch.timestamp_nanos,
    };
}

}  // namespace navonweb::openauto_port
