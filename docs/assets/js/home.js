(() => {
  const reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)');
  const video = document.querySelector('.app-demo video');
  const section = document.querySelector('#features');
  const sound = document.querySelector('#demo-sound');
  let sectionVisible = false;
  let userPaused = false;
  let automaticPause = false;

  function syncVideo() {
    if (!sectionVisible || document.hidden || reducedMotion.matches || userPaused) {
      if (!video.paused) {
        automaticPause = true;
        video.pause();
      }
    } else {
      video.play().catch(() => { /* Native controls remain available if autoplay is blocked. */ });
    }
  }
  if (video && section) {
    if (!video.paused) { automaticPause = true; video.pause(); }
    video.addEventListener('pause', () => {
      if (!automaticPause && sectionVisible && !document.hidden) userPaused = true;
      automaticPause = false;
    });
    video.addEventListener('play', () => { userPaused = false; });
    new IntersectionObserver(([entry]) => {
      sectionVisible = entry.isIntersecting;
      syncVideo();
    }).observe(section);
    sound.hidden = false;
    const updateSound = () => {
      const audible = !video.muted && video.volume > 0;
      sound.textContent = audible ? 'Mute sound' : 'Turn sound on';
      sound.setAttribute('aria-pressed', String(audible));
    };
    sound.addEventListener('click', () => {
      video.muted = !video.muted && video.volume > 0;
      if (!video.muted) {
        if (video.volume === 0) video.volume = 1;
        userPaused = false;
        video.play().catch(() => {});
      }
      updateSound();
    });
    video.addEventListener('volumechange', updateSound);
    document.addEventListener('visibilitychange', syncVideo);
    reducedMotion.addEventListener('change', syncVideo);
  }

  const track = document.querySelector('#screenshot-track');
  const controls = document.querySelector('.gallery-controls');
  if (!track || !controls) return;
  controls.hidden = false;
  const toggle = controls.querySelector('[data-gallery="toggle"]');
  let paused = reducedMotion.matches;
  let visible = false;
  let hovered = false;
  let focused = false;
  let timer;
  function slide(direction) {
    const step = track.children[1].offsetLeft - track.children[0].offsetLeft;
    const max = track.scrollWidth - track.clientWidth;
    let target = track.scrollLeft + direction * step;
    if (direction > 0 && track.scrollLeft >= max - 2) target = 0;
    if (direction < 0 && track.scrollLeft <= 2) target = max;
    track.scrollTo({ left: Math.min(max, Math.max(0, target)), behavior: reducedMotion.matches ? 'instant' : 'smooth' });
  }
  function schedule() {
    clearInterval(timer);
    toggle.textContent = paused ? 'Play slideshow' : 'Pause slideshow';
    toggle.setAttribute('aria-pressed', String(paused));
    if (!paused && visible && !hovered && !focused && !document.hidden) timer = setInterval(() => slide(1), 4500);
  }
  controls.addEventListener('click', (event) => {
    const action = event.target.closest('button')?.dataset.gallery;
    if (action === 'toggle') paused = !paused;
    else if (action === 'next' || action === 'previous') { paused = true; slide(action === 'next' ? 1 : -1); }
    schedule();
  });
  const gallery = document.querySelector('#inside');
  gallery.addEventListener('mouseenter', () => { hovered = true; schedule(); });
  gallery.addEventListener('mouseleave', () => { hovered = false; schedule(); });
  gallery.addEventListener('focusin', () => { focused = true; schedule(); });
  gallery.addEventListener('focusout', (event) => { focused = gallery.contains(event.relatedTarget); schedule(); });
  track.addEventListener('pointerdown', () => { paused = true; schedule(); });
  new IntersectionObserver(([entry]) => { visible = entry.isIntersecting; schedule(); }).observe(track);
  document.addEventListener('visibilitychange', schedule);
  reducedMotion.addEventListener('change', () => { paused = reducedMotion.matches; schedule(); });
  schedule();
})();
