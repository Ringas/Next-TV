// SPDX-License-Identifier: GPL-3.0-only
package com.nexttv.core.tv

import android.view.KeyEvent as K

/** Shared semantic vocabulary. Tab and text-editing keys remain owned by native controls. */
internal enum class InputAction{UP,DOWN,LEFT,RIGHT,CONFIRM,BACK,MENU,INFO,PLAY_PAUSE,SEEK_FORWARD,SEEK_BACKWARD,NEXT_CHANNEL,PREVIOUS_CHANNEL}
internal object TvInput {
 fun action(code:Int):InputAction?=when(code){
  K.KEYCODE_DPAD_UP->InputAction.UP;K.KEYCODE_DPAD_DOWN->InputAction.DOWN
  K.KEYCODE_DPAD_LEFT->InputAction.LEFT;K.KEYCODE_DPAD_RIGHT->InputAction.RIGHT
  K.KEYCODE_DPAD_CENTER,K.KEYCODE_ENTER,K.KEYCODE_NUMPAD_ENTER,K.KEYCODE_SPACE->InputAction.CONFIRM
  K.KEYCODE_BACK,K.KEYCODE_ESCAPE->InputAction.BACK
  K.KEYCODE_MENU->InputAction.MENU;K.KEYCODE_INFO->InputAction.INFO
  K.KEYCODE_MEDIA_PLAY_PAUSE->InputAction.PLAY_PAUSE
  K.KEYCODE_MEDIA_FAST_FORWARD->InputAction.SEEK_FORWARD;K.KEYCODE_MEDIA_REWIND->InputAction.SEEK_BACKWARD
  K.KEYCODE_CHANNEL_DOWN,K.KEYCODE_MEDIA_NEXT->InputAction.NEXT_CHANNEL
  K.KEYCODE_CHANNEL_UP,K.KEYCODE_MEDIA_PREVIOUS->InputAction.PREVIOUS_CHANNEL
  else->null
 }
 fun confirm(code:Int,includeSpace:Boolean=true)=action(code)==InputAction.CONFIRM&&(includeSpace||code!=K.KEYCODE_SPACE)
 fun back(code:Int)=action(code)==InputAction.BACK
 fun vertical(code:Int)=when(action(code)){InputAction.UP,InputAction.DOWN->true;else->false}
 fun horizontal(code:Int):Int?=when(action(code)){InputAction.LEFT->-1;InputAction.RIGHT->1;else->null}
 fun digit(code:Int):Int?=when(code){in K.KEYCODE_0..K.KEYCODE_9->code-K.KEYCODE_0;in K.KEYCODE_NUMPAD_0..K.KEYCODE_NUMPAD_9->code-K.KEYCODE_NUMPAD_0;else->null}
 fun directionKey(direction:SwipeDirection)=when(direction){SwipeDirection.UP->K.KEYCODE_DPAD_UP;SwipeDirection.DOWN->K.KEYCODE_DPAD_DOWN;SwipeDirection.LEFT->K.KEYCODE_DPAD_LEFT;SwipeDirection.RIGHT->K.KEYCODE_DPAD_RIGHT}
}
