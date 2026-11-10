/* ============================================================
   <phone-picker> — in-screen modal for choosing the paired iPhone.
   ============================================================ */

(function () {
  'use strict';

  const { computed, ref, onMounted } = Vue;
  const s = DiPlay.store;

  const PhonePicker = {
    name: 'PhonePicker',

    setup() {
      const t = computed(() => DiPlay.copy());
      const dialog = ref(null);

      onMounted(() => {
        if (dialog.value) dialog.value.focus();
      });

      function choose(phone) {
        if (s.scanning) return;
        DiPlay.actions.choosePhone(phone);
      }

      // Keep D-pad and Tab focus inside the dialog while it is open.
      function trapFocus(event) {
        if (!dialog.value) return;
        const nodes = dialog.value.querySelectorAll('button:not(:disabled)');
        if (!nodes.length) return;
        const first = nodes[0];
        const last = nodes[nodes.length - 1];
        if (event.shiftKey && (document.activeElement === first || document.activeElement === dialog.value)) {
          event.preventDefault();
          last.focus();
        } else if (!event.shiftKey && document.activeElement === last) {
          event.preventDefault();
          first.focus();
        }
      }

      return {
        s,
        t,
        dialog,
        choose,
        trapFocus,
        close: () => DiPlay.actions.closeSheet(),
        rescan: () => DiPlay.actions.rescan(),
      };
    },

    template: `
      <div class="modal-scrim" @click.self="close">
        <div
          class="modal"
          ref="dialog"
          role="dialog"
          aria-modal="true"
          aria-labelledby="phone-picker-title"
          tabindex="-1"
          @keydown.tab="trapFocus"
        >
          <header class="modal__head">
            <div>
              <h2 class="modal__title" id="phone-picker-title">{{ t.phonesTitle }}</h2>
              <p class="modal__sub">{{ s.scanning ? t.scanning : t.phonesHint }}</p>
            </div>
            <button class="icon-btn" type="button" @click="close" :aria-label="t.cancel">
              <svg width="16" height="16" viewBox="0 0 16 16" fill="none" aria-hidden="true">
                <path d="M3 3l10 10M13 3L3 13" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"/>
              </svg>
            </button>
          </header>

          <div v-if="s.scanning" class="scanline" aria-hidden="true"></div>

          <ul class="phone-list" :class="{ 'is-scanning': s.scanning }">
            <li v-for="phone in s.phones" :key="phone.name">
              <button
                class="phone"
                type="button"
                :class="{ 'is-chosen': phone.name === s.phoneName }"
                :disabled="s.scanning"
                @click="choose(phone)"
              >
                <span class="phone__icon">
                  <svg width="20" height="26" viewBox="0 0 20 26" fill="none" aria-hidden="true">
                    <rect x="1.2" y="1.2" width="17.6" height="23.6" rx="4" stroke="currentColor" stroke-width="1.6"/>
                    <path d="M8 4h4" stroke="currentColor" stroke-width="1.6" stroke-linecap="round"/>
                  </svg>
                </span>

                <span class="phone__text">
                  <span class="phone__name">{{ phone.name }}</span>
                  <span class="phone__tag">{{ phone.paired ? t.paired : t.nearby }}</span>
                </span>

                <span class="signal" :aria-hidden="true">
                  <i v-for="bar in 3" :key="bar" :class="{ on: bar <= phone.signal }"></i>
                </span>

                <span v-if="phone.name === s.phoneName" class="phone__check" aria-hidden="true">
                  <svg width="13" height="13" viewBox="0 0 14 14" fill="none">
                    <path d="M2.5 7.4l3 3 6-6.4" stroke="currentColor" stroke-width="2.1" stroke-linecap="round" stroke-linejoin="round"/>
                  </svg>
                </span>
              </button>
            </li>
          </ul>

          <footer class="modal__foot">
            <button class="btn btn--ghost btn--sm" type="button" @click="close">{{ t.cancel }}</button>
            <button class="btn btn--secondary btn--sm" type="button" @click="rescan">{{ t.rescan }}</button>
          </footer>
        </div>
      </div>
    `,
  };

  window.PhonePicker = PhonePicker;
})();
