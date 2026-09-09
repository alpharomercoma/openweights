/*
 * Copyright 2026 The OpenWeights Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.alpharomercoma.openweights.watch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * The alarm's landing: hands the wake-up to [AlarmTickWait], which resumes the ticker.
 *
 * Nothing runs here. The receiver's hold on the CPU ends the moment `onReceive` returns,
 * and the check is a model turn; [AlarmTickWait.awake] takes its own wake lock for that.
 * Not injected: the waiters are kept process-wide, so there is nothing to inject.
 */
class WatchAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AlarmTickWait.ACTION_TICK) return
        AlarmTickWait.fire(intent.getIntExtra(AlarmTickWait.EXTRA_CODE, -1))
    }
}
