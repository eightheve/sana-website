const COLLAPSED_CLASS = "is-collapsed";

function setupBody(body, index) {
  body.classList.add(COLLAPSED_CLASS);
  const overflows = body.scrollHeight > body.clientHeight;
  body.classList.remove(COLLAPSED_CLASS);

  if (!overflows) return;

  const id = `blog-post-body-${index}`;
  body.id = id;

  const post = body.closest(".blog-post");
  const heading = post && post.querySelector("h2");
  const title = heading ? heading.textContent.trim() : "";
  const label = (action) => (title ? `${action}: ${title}` : action);

  const button = document.createElement("button");
  button.type = "button";
  button.className = "blog-toggle";
  button.setAttribute("aria-expanded", "false");
  button.setAttribute("aria-controls", id);
  button.setAttribute("aria-label", label("Read more"));
  button.textContent = "Read more";

  button.addEventListener("click", () => {
    const collapsed = body.classList.toggle(COLLAPSED_CLASS);
    button.setAttribute("aria-expanded", String(!collapsed));
    button.setAttribute("aria-label", label(collapsed ? "Read more" : "Show less"));
    button.textContent = collapsed ? "Read more" : "Show less";
  });

  body.after(button);
  body.classList.add(COLLAPSED_CLASS);
}

function init() {
  document.querySelectorAll(".blog-post-body").forEach(setupBody);
}

window.addEventListener("load", init);
