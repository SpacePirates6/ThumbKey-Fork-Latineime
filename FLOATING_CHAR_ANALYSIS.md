# Floating Char Animation — Deep Dive Analysis

## Overview

The floating char animation shows characters briefly floating upward when you tap a key or select a suggestion. The implementation lives in `FloatingCharOverlay.kt` and is triggered from `KeyboardKey.kt` (key taps) and `KeyboardScreen.kt` (suggestion bar selections).

---

## Critical Bug: Coordinate System Mismatch

**This is likely the main cause of the animation "working poorly" or not appearing correctly.**

### The Problem

1. **Source coordinates** (from `KeyboardKey` and `KeyboardScreen`):
   - `keyScreenPosition = coordinates.positionOnScreen()` → **screen coordinates** (relative to device top-left)
   - `suggestionBarScreenCenterX/Y` = `coordinates.positionOnScreen()` → **screen coordinates**

2. **Overlay rendering** (in `FloatingCharOverlay`):
   - The overlay is added via `WindowManager.addView()` as a separate view in the IME window
   - The overlay uses `Modifier.offset { IntOffset(startX.toInt(), startY.toInt()) }` — which interprets values as **local coordinates** relative to the overlay's top-left (0,0)

3. **The mismatch**:
   - The IME window (keyboard) is typically at the **bottom** of the screen
   - So overlay (0,0) = IME window top-left ≈ screen position (0, ~1200–1800px on many devices)
   - We pass screen coords like (540, 1700) directly
   - The overlay draws at (540, 1700) in **its own** coordinate system
   - Result: characters are drawn **far below the visible overlay** (e.g. 1700px down when overlay is only ~400px tall)
   - **Characters may be entirely invisible or appear in wrong locations**

### The Fix

The overlay must convert screen coordinates to overlay-local coordinates by subtracting its own position on screen:

```kotlin
val localX = startX - overlayPositionOnScreen.x
val localY = startY - overlayPositionOnScreen.y
```

---

## Secondary Issues

### 1. Animation Doesn't Actually Go to Cursor

Despite the intuitive description "keys float up to where the cursor is":

- The animation only moves characters **up by a fixed 80dp** and fades them out
- There is no `endX`/`endY` target; no use of cursor position
- The cursor lives in the **app’s window** (the text field), while the overlay is in the **IME window** — so drawing into the cursor area from this overlay is not possible
- The effect is more like a "float up and disappear" burst from the key/suggestion bar

### 2. Redundant Alpha

```kotlin
color = color.copy(alpha = currentAlpha.coerceIn(0f, 1f))  // alpha on color
.alpha(currentAlpha.coerceIn(0f, 1f))                       // alpha modifier
```

Alpha is applied twice, so effective opacity is `currentAlpha²`, causing faster fade than intended.

### 3. Composable Iteration Over SnapshotStateList

```kotlin
floatingChars.toList().forEach { fc ->
    FloatingCharItem(...)
}
```

- `toList()` creates a snapshot at composition time — correct for avoiding concurrent modification
- `removeAll` in `onComplete` mutates the list after the animation — generally fine
- Still, iterating and composing from a snapshot list can be tricky; consider a keyed approach if there are ever ordering or removal quirks

### 4. Fixed Rise Distance

`risePx = 80.dp` is hardcoded. On small or large screens, this may feel too short or too long; making it configurable or density-relative could improve perception.

---

## Data Flow Summary

```
KeyboardKey tap / SuggestionBar selection
    ↓
emitFloatingChar(text, screenX, screenY)  [IMEService]
    ↓
floatingChars.add(FloatingChar(id, text, startX, startY))
    ↓
FloatingCharOverlay (in separate overlay view)
    ↓
FloatingCharItem uses startX, startY as offset  [BUG: treats screen coords as local]
    ↓
Character drawn at wrong position or invisible
```

---

## Recommended Fix Priority

1. **Critical**: Fix coordinate conversion (screen → overlay-local) in `FloatingCharOverlay`
2. **Minor**: Remove redundant alpha application
3. **Optional**: Make rise distance configurable or more adaptive to screen size
