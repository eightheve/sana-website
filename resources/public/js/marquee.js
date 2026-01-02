const p = document.querySelector(".marquee");
const s = p.querySelector("span");

const pause = ms => new Promise(r => setTimeout(r, ms));

async function run(iterations = 3) {
  const max = p.clientWidth - s.scrollWidth;
  if (max >= 0) return;

  await pause(150);
  for (let i = 0; i < iterations; i++) {
    s.style.transition = "transform 6s linear";
    s.style.transform = `translateX(${max}px)`;
    await pause(6000 + 2000);

    s.style.transition = "transform 6s linear";
    s.style.transform = `translateX(0)`;
    await pause(6000 + 2000);
  }
}

let running = false;

p.addEventListener("mouseenter", () => {
  if (running) return;
  running = true;
  run(3).finally(() => {
    running = false;
  });
});

