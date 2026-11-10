/* ============================================================
   <glass-home> — sky + two glass tiles + draggable USB / Wi-Fi pill,
   plus the ICCOA pairing choreography and the projection handoff.
   Glass is a Gaussian-blur approximation, not a refraction shader.
   ============================================================ */

(function () {
  'use strict';

  const { computed, ref, onMounted, onBeforeUnmount } = Vue;
  const s = DiPlay.store;

  const TRAVEL = 135;

  const GlassHome = {
    name: 'GlassHome',

    setup() {
      const t = computed(() => DiPlay.copy());
      const track = ref(null);
      const dragging = ref(false);
      const dragX = ref(0);
      const moved = ref(false);
      const suppressClick = ref(false);

      const pair = ref(null);
      const blackout = ref(false);
      const projection = ref(false);
      const projectionSource = ref('iccoa');

      let startX = 0;
      let startOffset = 0;
      let dpPerPx = 1;
      let timers = [];

      const selected = computed(() => (s.transport === 'wifi' ? 1 : 0));

      const thumbStyle = computed(() => ({
        transform: 'translateX(' + (dragging.value ? dragX.value : selected.value * TRAVEL) + 'px)',
      }));

      const pairExpanding = computed(() => pair.value && pair.value.stage === 'expanding');

      const codeDigits = computed(() =>
        pair.value && pair.value.code ? pair.value.code.split('') : [],
      );

      function later(fn, ms) {
        timers.push(window.setTimeout(fn, ms));
      }

      function clearTimers() {
        timers.forEach((timer) => window.clearTimeout(timer));
        timers = [];
      }

      function goCarHome() {
        DiPlay.actions.goCarHome();
      }

      function openSettings() {
        DiPlay.actions.openSettings();
      }

      function chooseCarplay() {
        if (pair.value) return;
        openCarplayPairing();
      }

      // --- ICCOA pairing sequence -------------------------------------
      function openPairing() {
        if (pair.value) return;
        clearTimers();
        blackout.value = false;
        projection.value = false;

        const code = String(Math.floor(100000 + Math.random() * 900000));
        pair.value = { source: 'iccoa', code, stage: 'waiting' };

        later(() => {
          if (pair.value) pair.value.stage = 'connected';
        }, 4500);
        later(() => {
          if (pair.value) pair.value.stage = 'expanding';
        }, 6000);
        later(() => {
          blackout.value = true;
        }, 7000);
        later(() => {
          pair.value = null;
          projectionSource.value = 'iccoa';
          projection.value = true;
        }, 7550);
        later(() => {
          blackout.value = false;
        }, 8150);
      }

      function openCarplayPairing() {
        if (pair.value) return;
        clearTimers();
        blackout.value = false;
        projection.value = false;

        const wireless = s.transport === 'wifi';
        const steps = wireless
          ? [
              t.value.pairCarplayStepHotspot,
              t.value.pairCarplayStepPaired,
              t.value.pairCarplayStepBluetooth,
              t.value.pairCarplayStepOpen,
            ]
          : [
              t.value.pairCarplayStepFind,
              t.value.pairCarplayStepUsb,
              t.value.pairCarplayStepPair,
              t.value.pairCarplayStepOpen,
            ];

        pair.value = {
          source: 'carplay',
          stage: 'waiting',
          step: 0,
          steps,
          transport: wireless ? 'Wi-Fi' : 'USB',
        };

        later(() => {
          if (pair.value && pair.value.source === 'carplay') pair.value.step = 1;
        }, 1100);
        later(() => {
          if (pair.value && pair.value.source === 'carplay') pair.value.step = 2;
        }, 2300);
        later(() => {
          if (pair.value && pair.value.source === 'carplay') pair.value.step = 3;
        }, 3500);
        later(() => {
          if (pair.value) pair.value.stage = 'connected';
        }, 4700);
        later(() => {
          if (pair.value) pair.value.stage = 'expanding';
        }, 5700);
        later(() => {
          blackout.value = true;
        }, 6700);
        later(() => {
          pair.value = null;
          projectionSource.value = 'carplay';
          projection.value = true;
        }, 7250);
        later(() => {
          blackout.value = false;
        }, 7850);
      }

      function closePairing() {
        if (!pair.value || pair.value.stage === 'expanding') return;
        clearTimers();
        pair.value = null;
      }

      function exitProjection() {
        projection.value = false;
      }

      function onKeydown(event) {
        if (event.key !== 'Escape') return;
        if (projection.value) {
          exitProjection();
          return;
        }
        if (pair.value) closePairing();
      }

      // --- Transport drag ---------------------------------------------
      function chooseSide(side) {
        if (suppressClick.value) {
          suppressClick.value = false;
          return;
        }
        DiPlay.actions.setTransport(side);
      }

      function beginAt(clientX) {
        const rect = track.value ? track.value.getBoundingClientRect() : null;
        dpPerPx = rect && rect.width ? rect.width / 274 : 1;
        startX = clientX;
        startOffset = selected.value * TRAVEL;
        dragX.value = startOffset;
        moved.value = false;
        dragging.value = true;
      }

      function moveTo(clientX) {
        if (!dragging.value) return;
        const dx = (clientX - startX) / (dpPerPx || 1);
        if (!moved.value && Math.abs(dx) > 10) moved.value = true;
        dragX.value = Math.max(0, Math.min(TRAVEL, startOffset + dx));
      }

      function finish() {
        if (!dragging.value) return;
        dragging.value = false;
        window.removeEventListener('pointermove', onPointerMove);
        window.removeEventListener('pointerup', finish);
        window.removeEventListener('pointercancel', finish);
        window.removeEventListener('touchmove', onTouchMove);
        window.removeEventListener('touchend', finish);
        window.removeEventListener('touchcancel', finish);
        if (!moved.value) return;
        DiPlay.actions.setTransport(dragX.value > TRAVEL / 2 ? 'wifi' : 'usb');
        suppressClick.value = true;
        window.setTimeout(() => {
          suppressClick.value = false;
        }, 320);
      }

      function onDown(event) {
        if (event.pointerType && event.pointerType !== 'mouse') return;
        if (event.button !== undefined && event.button !== 0) return;
        beginAt(event.clientX);
        window.addEventListener('pointermove', onPointerMove);
        window.addEventListener('pointerup', finish);
        window.addEventListener('pointercancel', finish);
      }

      function onPointerMove(event) {
        moveTo(event.clientX);
      }

      function onTouchStart(event) {
        if (event.touches.length !== 1) return;
        beginAt(event.touches[0].clientX);
        window.addEventListener('touchmove', onTouchMove, { passive: false });
        window.addEventListener('touchend', finish);
        window.addEventListener('touchcancel', finish);
      }

      function onTouchMove(event) {
        if (!dragging.value || !event.touches.length) return;
        moveTo(event.touches[0].clientX);
        if (moved.value) event.preventDefault();
      }

      onMounted(() => window.addEventListener('keydown', onKeydown));
      onBeforeUnmount(() => {
        finish();
        clearTimers();
        window.removeEventListener('keydown', onKeydown);
      });

      return {
        s,
        t,
        track,
        dragging,
        thumbStyle,
        pair,
        pairExpanding,
        codeDigits,
        blackout,
        projection,
        projectionSource,
        goCarHome,
        openSettings,
        chooseCarplay,
        openPairing,
        closePairing,
        exitProjection,
        chooseSide,
        onDown,
        onTouchStart,
      };
    },

    template: `
      <div
        class="glass-home"
        :class="{
          'is-pairing': !!pair,
          'is-projecting': projection,
          'is-expanding': pairExpanding,
        }"
      >
        <div v-if="s.chrome" class="gh-gesture" aria-hidden="true"></div>

        <div class="gh-sky" aria-hidden="true">
          <span class="gh-sky__wash gh-sky__wash--cool"></span>
          <span class="gh-sky__wash gh-sky__wash--warm"></span>
          <span class="gh-sky__streak gh-sky__streak--top"></span>
          <span class="gh-sky__streak gh-sky__streak--bottom"></span>
          <span class="gh-sky__grain"></span>
        </div>

        <div class="gh-top">
          <button class="gh-topbtn" type="button" :aria-label="t.carHome" @click="goCarHome">
            <svg class="gh-topbtn__icon" aria-hidden="true"><use href="#i-car"></use></svg>
          </button>
          <button class="gh-topbtn" type="button" :aria-label="t.settings" @click="openSettings">
            <svg class="gh-topbtn__icon" aria-hidden="true"><use href="#i-gear"></use></svg>
          </button>
        </div>

        <div class="gh-cards" :inert="pair || projection ? true : null">
          <button
            class="gh-card gh-card--carplay"
            type="button"
            aria-label="CarPlay"
            @click="chooseCarplay"
          >
            <span class="gh-card__sheen" aria-hidden="true"></span>
            <span class="gh-card__icon gh-card__icon--carplay">
              <img src="./assets/carplay.png" alt="" draggable="false">
            </span>
            <span class="gh-card__label">CarPlay</span>
          </button>

          <button
            class="gh-card gh-card--iccoa"
            type="button"
            aria-label="ICCOA"
            @click="openPairing"
          >
            <span class="gh-card__sheen" aria-hidden="true"></span>
            <span class="gh-card__icon gh-card__icon--iccoa">
              <svg class="gh-card__link" aria-hidden="true"><use href="#i-link"></use></svg>
            </span>
            <span class="gh-card__label">ICCOA</span>
          </button>
        </div>

        <div
          class="gh-transport"
          :inert="pair || projection ? true : null"
          ref="track"
          role="radiogroup"
          aria-label="Connection: USB or Wi-Fi"
          @pointerdown="onDown"
          @touchstart="onTouchStart"
        >
          <span
            class="gh-transport__thumb"
            :class="{ 'is-dragging': dragging }"
            :style="thumbStyle"
            aria-hidden="true"
          ></span>

          <button
            class="gh-transport__opt"
            type="button"
            role="radio"
            :aria-checked="s.transport === 'usb'"
            :class="{ 'is-on': s.transport === 'usb' }"
            @click="chooseSide('usb')"
          >
            <svg class="gh-transport__icon" aria-hidden="true"><use href="#i-usb"></use></svg>
            <span class="sr-only">USB</span>
          </button>

          <button
            class="gh-transport__opt"
            type="button"
            role="radio"
            :aria-checked="s.transport === 'wifi'"
            :class="{ 'is-on': s.transport === 'wifi' }"
            @click="chooseSide('wifi')"
          >
            <svg class="gh-transport__icon" aria-hidden="true"><use href="#i-wifi"></use></svg>
            <span class="sr-only">Wi-Fi</span>
          </button>
        </div>

        <transition name="pair">
          <div
            v-if="pair"
            class="pair-scrim"
            :class="{ 'is-expanding': pairExpanding }"
            @click.self="closePairing"
          >
            <section
              class="pair-card"
              :class="{
                'is-carplay': pair.source === 'carplay',
                'is-connected': pair.stage === 'connected',
                'is-expanding': pair.stage === 'expanding',
              }"
              role="dialog"
              aria-modal="true"
              aria-labelledby="pair-title"
            >
              <header class="pair-head">
                <span
                  class="pair-head__icon"
                  :class="{ 'is-carplay': pair.source === 'carplay' }"
                  aria-hidden="true"
                >
                  <img v-if="pair.source === 'carplay'" src="./assets/carplay.png" alt="">
                  <svg v-else><use href="#i-link"></use></svg>
                </span>
                <h2 class="pair-head__title" id="pair-title">
                  {{ pair.source === 'carplay' ? 'CarPlay' : 'ICCOA' }}
                </h2>
                <button
                  v-if="pair.stage !== 'expanding'"
                  class="pair-close"
                  type="button"
                  :aria-label="t.cancel"
                  @click="closePairing"
                >
                  <svg aria-hidden="true"><use href="#i-close"></use></svg>
                </button>
              </header>

              <span class="pair-veil" aria-hidden="true"></span>

              <p class="pair-prompt">
                {{ pair.source === 'carplay' ? t.pairCarplayPrompt : t.pairEnterCode }}
              </p>

              <template v-if="pair.source === 'carplay'">
                <p class="pair-meta">{{ pair.transport }} · {{ s.phoneName }}</p>
                <ol class="pair-steps">
                  <li
                    v-for="(step, index) in pair.steps"
                    :key="step"
                    class="pair-step"
                    :class="{
                      'is-done': pair.stage === 'connected' || index < pair.step,
                      'is-active': pair.stage !== 'connected' && index === pair.step,
                    }"
                  >
                    <span class="pair-step__dot" aria-hidden="true">
                      <svg v-if="pair.stage === 'connected' || index < pair.step">
                        <use href="#i-check"></use>
                      </svg>
                      <span v-else-if="index === pair.step" class="pair-step__spin"></span>
                    </span>
                    <span class="pair-step__label">{{ step }}</span>
                  </li>
                </ol>
              </template>

              <div v-else class="pair-code" :aria-label="pair.code">
                <span
                  v-for="(digit, index) in codeDigits"
                  :key="index"
                  :style="{ animationDelay: index * 55 + 'ms' }"
                >{{ digit }}</span>
              </div>

              <div class="pair-line" aria-hidden="true"><i></i></div>
              <p v-if="pair.source === 'iccoa'" class="pair-valid">{{ t.pairValid }}</p>
              <p v-else class="pair-hint">{{ t.pairCarplayHint }}</p>

              <div class="pair-status" aria-live="polite">
                <template v-if="pair.stage === 'connected'">
                  <span class="pair-check" aria-hidden="true">
                    <svg><use href="#i-check"></use></svg>
                  </span>
                  <span>{{ t.pairConnected }}</span>
                </template>
                <template v-else>
                  <span class="pair-spinner" aria-hidden="true"></span>
                  <span>{{ pair.source === 'carplay' ? t.connecting : t.pairWaiting }}</span>
                </template>
              </div>
            </section>
          </div>
        </transition>

        <transition name="blackout">
          <div v-if="blackout" class="gh-blackout"></div>
        </transition>

        <transition name="proj">
          <div
            v-if="projection"
            class="iccoa-projection"
            :class="{ 'is-carplay': projectionSource === 'carplay' }"
          >
            <div class="proj-glow" aria-hidden="true"></div>
            <div class="proj-body">
              <span
                class="proj-mark"
                :class="{ 'proj-mark--carplay': projectionSource === 'carplay' }"
                aria-hidden="true"
              >
                <img
                  v-if="projectionSource === 'carplay'"
                  class="proj-mark__img"
                  src="./assets/carplay.png"
                  alt=""
                >
                <svg v-else><use href="#i-link"></use></svg>
              </span>
              <h2 class="proj-title">
                {{ projectionSource === 'carplay' ? 'CarPlay' : 'ICCOA' }}
              </h2>
              <p class="proj-state">{{ t.pairConnected }}</p>
            </div>
            <p class="proj-hint">
              {{ projectionSource === 'carplay' ? t.projectionCarplayHint : t.projectionHint }}
            </p>
            <button class="proj-back" type="button" @click="exitProjection">
              <svg aria-hidden="true"><use href="#i-back"></use></svg>
              <span>{{ t.back }}</span>
            </button>
          </div>
        </transition>
      </div>
    `,
  };

  window.GlassHome = GlassHome;
})();

