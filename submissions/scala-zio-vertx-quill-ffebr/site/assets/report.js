const controls = document.querySelector("#trace-controls");
const caption = document.querySelector("#trace-caption");
const buttons = controls.querySelectorAll("[data-step]");
const cells = document.querySelectorAll("[data-phase]");

const captions = [
  "JDBC отправляет A и B из двух синхронных вызовов. Vert.x ставит обе записи на отправку через event loop.",
  "Во время ожидания оба JDBC-потока заняты своими вызовами. Fibers A и B приостановлены, event loop может обслуживать другие события.",
  "Сокет B готов к чтению. JDBC-поток B получает ответ. В Vert.x event loop разбирает ответ B, а завершение Future позволяет ZIO возобновить fiber B. Запрос A ещё выполняется.",
  "Приходит ответ A. Последний JDBC-вызов возвращается, а в Vert.x завершение Future делает fiber A готовой к продолжению.",
];

function selectStep(step) {
  for (const button of buttons) {
    button.setAttribute("aria-pressed", String(button.dataset.step === step));
  }
  for (const cell of cells) {
    cell.classList.toggle("selected", cell.dataset.phase === step);
  }
  caption.textContent = captions[Number(step) - 1];
}

for (const button of buttons) {
  button.addEventListener("click", () => selectStep(button.dataset.step));
}

selectStep("2");
controls.hidden = false;
