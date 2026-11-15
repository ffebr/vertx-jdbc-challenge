(() => {
  const toggle = document.querySelector("#option-toggle");
  if (toggle) {
    const state = document.querySelector("#option-state");
    const source = document.querySelector("#binding-source");
    const value = document.querySelector("#binding-value");
    const type = document.querySelector("#binding-type");
    const result = document.querySelector("#binding-result");
    const parameterTypes = "$1 = text · $2 = text";

    const render = () => {
      const present = toggle.checked;
      state.textContent = present ? 'Some("new@example.test")' : "None";
      source.textContent = present
        ? 'lift(Some("new@example.test")).getOrElse(column)'
        : "lift(None).getOrElse(column)";
      value.textContent = present
        ? '$1 = "new@example.test" · $2 = "new@example.test"'
        : "$1 = NULL · $2 = NULL";
      type.textContent = parameterTypes;
      result.textContent = present
        ? 'Ожидаемое значение email: "new@example.test" будет записано.'
        : "Ожидаемое значение email: текущее значение сохранится.";
    };

    render();
    toggle.addEventListener("change", render);
  }

  const links = [...document.querySelectorAll(".rail nav a")];
  if ("IntersectionObserver" in window) {
    const observer = new IntersectionObserver(
      (entries) => {
        for (const entry of entries) {
          if (!entry.isIntersecting) continue;
          links.forEach((link) => {
            link.classList.toggle(
              "active",
              link.hash === `#${entry.target.id}`,
            );
          });
        }
      },
      { rootMargin: "-18% 0px -70% 0px" },
    );
    document
      .querySelectorAll(".chapter[id]")
      .forEach((section) => observer.observe(section));
  }
})();
