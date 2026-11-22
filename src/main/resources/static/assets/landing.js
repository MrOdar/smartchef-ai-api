// 1. Barra de Progreso de Lectura
const progressBar = document.getElementById('scroll-progress');
window.addEventListener('scroll', () => {
  const totalHeight = document.documentElement.scrollHeight - window.innerHeight;
  const progress = totalHeight > 0 ? (window.scrollY / totalHeight) * 100 : 0;
  progressBar.style.width = `${progress}%`;
});

// 2. Scroll Reveal Animations con Intersection Observer
const observer = new IntersectionObserver((entries) => {
  entries.forEach(entry => {
    if (entry.isIntersecting) {
      entry.target.classList.add('is-inview');
    }
  });
}, { threshold: 0.1 });

document.querySelectorAll('[data-scroll-reveal]').forEach(el => observer.observe(el));

// ==================== CONMUTADOR DE NAVEGACIÓN (BARRA vs DOCK) ====================
const btnSwitchToDock = document.getElementById('btn-switch-to-dock');
const btnSwitchToTopbar = document.getElementById('btn-switch-to-topbar');

function setNavMode(mode) {
  document.body.setAttribute('data-nav-mode', mode);
  localStorage.setItem('smartchef-nav-mode', mode);
}

const savedNavMode = localStorage.getItem('smartchef-nav-mode') || 'topbar';
setNavMode(savedNavMode);

btnSwitchToDock.addEventListener('click', () => {
  setNavMode('dock');
});

btnSwitchToTopbar.addEventListener('click', () => {
  setNavMode('topbar');
});

// ==================== ANIMATED THEME TOGGLER (GPU NATIVE 60FPS) ====================
const themeBtn = document.getElementById('theme-btn');
const dockThemeBtn = document.getElementById('dock-theme-toggle');
const themeIcon = document.getElementById('theme-icon');

function applyTheme(targetTheme) {
  document.documentElement.setAttribute('data-theme', targetTheme);
  localStorage.setItem('smartchef-theme', targetTheme);
  const icon = targetTheme === 'light' ? '🌙' : '☀️';
  if (themeIcon) themeIcon.textContent = icon;
  if (dockThemeBtn) dockThemeBtn.textContent = icon;
}

function toggleThemeAnimated(e) {
  const currentTheme = document.documentElement.getAttribute('data-theme') || 'light';
  const targetTheme = currentTheme === 'light' ? 'dark' : 'light';

  if (!document.startViewTransition || window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
    applyTheme(targetTheme);
    return;
  }

  const rect = e && e.currentTarget ? e.currentTarget.getBoundingClientRect() : null;
  const x = e && e.clientX ? e.clientX : (rect ? rect.left + rect.width / 2 : window.innerWidth / 2);
  const y = e && e.clientY ? e.clientY : (rect ? rect.top + rect.height / 2 : window.innerHeight / 2);
  const maxRadius = Math.hypot(
    Math.max(x, window.innerWidth - x),
    Math.max(y, window.innerHeight - y)
  );

  document.documentElement.style.setProperty('--reveal-x', `${x}px`);
  document.documentElement.style.setProperty('--reveal-y', `${y}px`);
  document.documentElement.style.setProperty('--reveal-radius', `${maxRadius}px`);

  document.startViewTransition(() => {
    applyTheme(targetTheme);
  });
}

// Inicializar tema
const savedTheme = localStorage.getItem('smartchef-theme') || 'light';
applyTheme(savedTheme);

themeBtn.addEventListener('click', toggleThemeAnimated);
dockThemeBtn.addEventListener('click', toggleThemeAnimated);

// ==================== GLARE HOVER (3D TILT & GLOSS) ====================
document.querySelectorAll('.glare-card').forEach(card => {
  card.addEventListener('mousemove', e => {
    const rect = card.getBoundingClientRect();
    const x = e.clientX - rect.left;
    const y = e.clientY - rect.top;
    const centerX = rect.width / 2;
    const centerY = rect.height / 2;

    const rotateX = ((y - centerY) / centerY) * -6;
    const rotateY = ((x - centerX) / centerX) * 6;

    card.style.transform = `perspective(1000px) rotateX(${rotateX.toFixed(2)}deg) rotateY(${rotateY.toFixed(2)}deg) translateY(-3px)`;
    card.style.setProperty('--gx', `${((x / rect.width) * 100).toFixed(1)}%`);
    card.style.setProperty('--gy', `${((y / rect.height) * 100).toFixed(1)}%`);
  });

  card.addEventListener('mouseleave', () => {
    card.style.transform = 'perspective(1000px) rotateX(0deg) rotateY(0deg) translateY(0)';
  });
});

// ==================== NUMBER TICKER ANIMATION (MAGIC UI) ====================
function animateNumber(element, start, end, duration, prefix = '', suffix = '') {
  let startTimestamp = null;
  const step = (timestamp) => {
    if (!startTimestamp) startTimestamp = timestamp;
    const progress = Math.min((timestamp - startTimestamp) / duration, 1);
    const easeOut = 1 - Math.pow(1 - progress, 3);
    const current = Math.floor(easeOut * (end - start) + start);
    element.textContent = `${prefix}${current.toLocaleString('es-PE')}${suffix}`;
    if (progress < 1) {
      window.requestAnimationFrame(step);
    } else {
      element.textContent = `${prefix}${end.toLocaleString('es-PE')}${suffix}`;
    }
  };
  window.requestAnimationFrame(step);
}

// ==================== CALCULADORA DE AHORRO ====================
const peopleInput = document.getElementById('people-slider');
const mealsInput = document.getElementById('meals-slider');
const peopleVal = document.getElementById('people-val');
const mealsVal = document.getElementById('meals-val');
const mealsDesc = document.getElementById('meals-desc');

const moneySaved = document.getElementById('money-saved');
const foodSaved = document.getElementById('food-saved');
const hoursSaved = document.getElementById('hours-saved');
const recipesCount = document.getElementById('recipes-count');

let currentMoney = 1350;
let currentFood = 78;
let currentHours = 16;
let currentRecipes = 140;

function updateCalculations() {
  const p = parseInt(peopleInput.value, 10);
  const m = parseInt(mealsInput.value, 10);

  peopleVal.textContent = `${p} ${p === 1 ? 'persona' : 'personas'}`;
  mealsVal.textContent = `${m} comidas / semana`;

  if (m <= 5) {
    mealsDesc.textContent = "Comidas de fin de semana o días libres en casa.";
  } else if (m <= 9) {
    mealsDesc.textContent = "Aprox. 1 comida al día en casa (ej. solo almuerzos o solo cenas).";
  } else if (m <= 15) {
    mealsDesc.textContent = "Almuerzo y cena habituales la mayor parte de la semana.";
  } else {
    mealsDesc.textContent = "Desayuno, almuerzo y cena preparados en casa casi todos los días (hasta 21).";
  }

  const targetMoney = Math.round(p * m * 45);
  const targetFood = Math.round(p * m * 2.6);
  const targetHours = Math.round(4 + (m * 1.2));
  const targetRecipes = Math.round(m * 14);

  animateNumber(moneySaved, currentMoney, targetMoney, 350, 'S/ ');
  animateNumber(foodSaved, currentFood, targetFood, 350, '', ' kg');
  animateNumber(hoursSaved, currentHours, targetHours, 350, '', ' hrs');
  animateNumber(recipesCount, currentRecipes, targetRecipes, 350, '+');

  currentMoney = targetMoney;
  currentFood = targetFood;
  currentHours = targetHours;
  currentRecipes = targetRecipes;
}

peopleInput.addEventListener('input', updateCalculations);
mealsInput.addEventListener('input', updateCalculations);

// ==================== DOCK MAGNIFICATION (MAGIC UI DOCK) ====================
const dockContainer = document.getElementById('floating-dock');
const dockButtons = dockContainer.querySelectorAll('.dock-icon-btn');

dockButtons.forEach((btn, index) => {
  btn.addEventListener('mouseenter', () => {
    if (dockButtons[index - 1]) {
      dockButtons[index - 1].style.transform = 'scale(1.15) translateY(-4px)';
    }
    if (dockButtons[index + 1]) {
      dockButtons[index + 1].style.transform = 'scale(1.15) translateY(-4px)';
    }
  });

  btn.addEventListener('mouseleave', () => {
    if (dockButtons[index - 1]) {
      dockButtons[index - 1].style.transform = '';
    }
    if (dockButtons[index + 1]) {
      dockButtons[index + 1].style.transform = '';
    }
  });
});
