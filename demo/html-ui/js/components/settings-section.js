/* ============================================================
   <settings-section> — a glass card under a heading.
   ============================================================ */

(function () {
  'use strict';

  const { computed } = Vue;
  const s = DiPlay.store;

  const SettingsSection = {
    name: 'SettingsSection',

    props: {
      section: { type: Object, required: true },
      highlight: { type: String, default: '' },
    },

    emits: ['choose', 'run'],

    setup() {
      const t = computed(() => DiPlay.copy());
      const label = (value) => (value ? value[s.lang] || value.en : '');

      function valueOf(control) {
        return DiPlay.actions.getPref(control.key, control.scope);
      }

      function optionLabel(control) {
        const options = control.options || [];
        const found = options.find((option) => option.value === valueOf(control));
        return label((found || options[0] || {}).label);
      }

      function toggle(control) {
        DiPlay.actions.setPref(control.key, !valueOf(control), control.scope);
      }

      function rowId(control) {
        return control.key || control.id;
      }

      return { s, t, label, optionLabel, toggle, rowId, valueOf };
    },

    template: `
      <section class="s-section">
        <header class="s-section__head">
          <svg class="s-section__icon" aria-hidden="true"><use :href="'#i-' + section.icon"></use></svg>
          <h2 class="s-section__title">{{ label(section.title) }}</h2>
        </header>

        <p v-if="section.note" class="s-section__note">{{ label(section.note) }}</p>

        <div class="s-glass">
          <template v-for="control in section.controls" :key="control.key || control.id">
            <div
              v-if="control.type === 'toggle'"
              class="s-row s-row--toggle"
              :data-setting="rowId(control)"
              :class="{ 'is-hit': highlight === rowId(control) }"
            >
              <div class="s-row__text">
                <p class="s-row__title">{{ label(control.title) }}</p>
                <p v-if="control.desc" class="s-row__desc">{{ label(control.desc) }}</p>
              </div>
              <button
                type="button"
                class="switch"
                role="switch"
                :aria-checked="!!valueOf(control)"
                :aria-label="label(control.title)"
                :class="{ 'is-on': !!valueOf(control) }"
                @click="toggle(control)"
              >
                <span class="switch__thumb"></span>
              </button>
            </div>

            <button
              v-else-if="control.type === 'choice'"
              type="button"
              class="s-row s-row--link"
              :data-setting="rowId(control)"
              :class="{ 'is-hit': highlight === rowId(control) }"
              @click="$emit('choose', control)"
            >
              <span class="s-row__text">
                <span class="s-row__title">{{ label(control.title) }}</span>
                <span v-if="control.desc" class="s-row__desc">{{ label(control.desc) }}</span>
              </span>
              <span class="s-row__value">{{ optionLabel(control) }}</span>
              <svg class="s-row__chev" aria-hidden="true"><use href="#i-chevron"></use></svg>
            </button>

            <button
              v-else-if="control.type === 'action'"
              type="button"
              class="s-row s-row--link"
              :data-setting="rowId(control)"
              :class="{ 'is-hit': highlight === rowId(control) }"
              @click="$emit('run', control)"
            >
              <span class="s-row__text">
                <span class="s-row__title">{{ label(control.title) }}</span>
                <span v-if="control.desc" class="s-row__desc">{{ label(control.desc) }}</span>
              </span>
              <svg class="s-row__chev" aria-hidden="true"><use href="#i-chevron"></use></svg>
            </button>

            <button
              v-else-if="control.type === 'phone'"
              type="button"
              class="s-row s-row--link"
              :data-setting="rowId(control)"
              :class="{ 'is-hit': highlight === rowId(control) }"
              @click="$emit('run', control)"
            >
              <span class="s-row__text">
                <span class="s-row__title">{{ label(control.title) }}</span>
              </span>
              <span class="s-row__value">{{ s.phoneName }}</span>
              <svg class="s-row__chev" aria-hidden="true"><use href="#i-chevron"></use></svg>
            </button>

            <div v-else-if="control.type === 'info'" class="s-row">
              <div class="s-row__text">
                <p class="s-row__title">{{ label(control.title) }}</p>
              </div>
              <span class="s-row__value">{{ control.value }}</span>
            </div>

            <p v-else-if="control.type === 'note'" class="s-note">{{ label(control.text) }}</p>
          </template>
        </div>
      </section>
    `,
  };

  window.SettingsSection = SettingsSection;
})();
