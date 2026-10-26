const COLLAPSED_CLASS = "is-collapsed";

function setupBody(body, index) {
  if (body.dataset.collapsibleReady) return;
  body.dataset.collapsibleReady = "true";

  body.classList.add(COLLAPSED_CLASS);
  const overflows = body.scrollHeight > body.clientHeight;
  body.classList.remove(COLLAPSED_CLASS);

  if (!overflows) return;

  const id = body.id || `blog-post-body-${index}`;
  body.id = id;

  const button = document.createElement("button");
  button.type = "button";
  button.className = "blog-toggle";
  button.setAttribute("aria-expanded", "false");
  button.setAttribute("aria-controls", id);
  button.textContent = "Read more";

  button.addEventListener("click", () => {
    const collapsed = body.classList.toggle(COLLAPSED_CLASS);
    button.setAttribute("aria-expanded", String(!collapsed));
    button.textContent = collapsed ? "Read more" : "Show less";
  });

  body.after(button);
  body.classList.add(COLLAPSED_CLASS);
}

function init() {
  document.querySelectorAll(".blog-post-body").forEach(setupBody);
}

if (document.readyState === "complete") {
  init();
} else {
  window.addEventListener("load", init);
}
