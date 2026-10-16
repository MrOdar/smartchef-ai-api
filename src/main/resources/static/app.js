/* SmartChef AI - logica de la SPA */
(function () {
    'use strict';

    var API = '/api/v1';
    var MAX_BYTES = 15 * 1024 * 1024;
    var ALLOWED_TYPES = ['image/jpeg', 'image/jpg', 'image/png', 'image/webp'];
    var TOKEN_KEY = 'smartchef.token';
    var USER_KEY = 'smartchef.user';
    var DEMO_IMAGE = 'assets/demo-fridge.jpg';

    /**
     * Unica URL fija del proyecto, y es deliberada: el QR se genera siempre con
     * la direccion viva del navegador, pero en localhost esa direccion no sirve
     * de nada en un telefono (resolveria su propio localhost). Solo en ese caso
     * se recurre a la direccion publica de produccion.
     */
    var PRODUCTION_URL = 'https://smartchef-ai-api.onrender.com/';

    /** Mensajes rotativos mientras la IA analiza, para amenizar la espera. */
    var LOADER_MESSAGES = [
        'Detectando alimentos en la fotografia...',
        'Identificando ingredientes frescos y de despensa...',
        'Buscando combinaciones que aprovechen lo que tienes...',
        'Calculando tiempos de coccion y calorias...',
        'Redactando los pasos y el consejo del chef...',
        'Dando los ultimos retoques a tus 3 recetas...'
    ];

    var state = {
        file: null,
        token: localStorage.getItem(TOKEN_KEY) || null,
        user: safeParse(localStorage.getItem(USER_KEY)),
        lastScan: null
    };

    // --- Utilidades ------------------------------------------------------

    function $(id) {
        return document.getElementById(id);
    }

    function safeParse(raw) {
        try {
            return raw ? JSON.parse(raw) : null;
        } catch (e) {
            return null;
        }
    }

    function show(el) {
        el.classList.remove('is-hidden');
    }

    function hide(el) {
        el.classList.add('is-hidden');
    }

    function escapeHtml(value) {
        return String(value == null ? '' : value)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#39;');
    }

    function formatSize(bytes) {
        if (bytes < 1024) {
            return bytes + ' B';
        }
        if (bytes < 1024 * 1024) {
            return (bytes / 1024).toFixed(0) + ' KB';
        }
        return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
    }

    function formatDate(iso) {
        if (!iso) {
            return '';
        }
        var date = new Date(iso);
        if (isNaN(date.getTime())) {
            return iso;
        }
        return date.toLocaleString('es', {
            day: '2-digit', month: 'short', year: 'numeric',
            hour: '2-digit', minute: '2-digit'
        });
    }

    var toastTimer = null;

    function toast(message) {
        var el = $('toast');
        el.textContent = message;
        show(el);
        clearTimeout(toastTimer);
        toastTimer = setTimeout(function () {
            hide(el);
        }, 3200);
    }

    function showAlert(message) {
        var el = $('alert');
        el.textContent = message;
        show(el);
    }

    function clearAlert() {
        hide($('alert'));
    }

    // --- Cliente HTTP ----------------------------------------------------

    function request(path, options) {
        var config = options || {};
        var headers = config.headers || {};

        if (state.token) {
            headers['Authorization'] = 'Bearer ' + state.token;
        }

        return fetch(API + path, {
            method: config.method || 'GET',
            headers: headers,
            body: config.body
        }).then(function (response) {
            return response.text().then(function (text) {
                var payload = text ? safeParse(text) : null;

                if (!response.ok) {
                    if (response.status === 401 && state.token) {
                        logout(true);
                    }
                    var message = (payload && (payload.message || payload.error))
                        || 'Error ' + response.status;
                    throw new Error(message);
                }
                return payload;
            });
        });
    }

    function jsonRequest(path, method, body) {
        return request(path, {
            method: method,
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(body)
        });
    }

    // --- Sesion ----------------------------------------------------------

    function isLoggedIn() {
        return Boolean(state.token);
    }

    function applySession(auth) {
        state.token = auth.token;
        state.user = auth.user;
        localStorage.setItem(TOKEN_KEY, auth.token);
        localStorage.setItem(USER_KEY, JSON.stringify(auth.user));
        renderSession();
    }

    function logout(silent) {
        state.token = null;
        state.user = null;
        localStorage.removeItem(TOKEN_KEY);
        localStorage.removeItem(USER_KEY);
        renderSession();
        if (!silent) {
            toast('Sesion cerrada');
        }
        switchView('scan');
    }

    function renderSession() {
        var chip = $('userchip');
        var button = $('btn-auth');

        if (isLoggedIn() && state.user) {
            chip.textContent = state.user.fullName || state.user.username;
            show(chip);
            button.textContent = 'Salir';
        } else {
            hide(chip);
            button.textContent = 'Iniciar sesion';
        }
    }

    // --- Navegacion ------------------------------------------------------

    function switchView(name) {
        ['scan', 'favorites', 'history'].forEach(function (view) {
            $('view-' + view).classList.toggle('is-active', view === name);
        });
        document.querySelectorAll('.navlink[data-view]').forEach(function (link) {
            link.classList.toggle('is-active', link.dataset.view === name);
        });

        if (name === 'favorites') {
            loadFavorites();
        } else if (name === 'history') {
            loadHistory();
        }
    }

    // --- Seleccion de archivo --------------------------------------------

    function handleFile(file) {
        clearAlert();

        if (!file) {
            return;
        }
        if (ALLOWED_TYPES.indexOf(file.type) === -1) {
            showAlert('Formato no soportado. Usa una imagen JPEG, PNG o WEBP.');
            return;
        }
        if (file.size > MAX_BYTES) {
            showAlert('La imagen pesa ' + formatSize(file.size) + '. El maximo permitido es 15MB.');
            return;
        }

        state.file = file;

        var reader = new FileReader();
        reader.onload = function (event) {
            $('preview-img').src = event.target.result;
            $('preview-name').textContent = file.name;
            $('preview-size').textContent = formatSize(file.size);
            hide($('dropzone-empty'));
            show($('preview'));
            $('dropzone').classList.add('has-file');
            $('btn-analyze').disabled = false;
            $('btn-reset').hidden = false;
        };
        reader.readAsDataURL(file);
    }

    function resetFile() {
        state.file = null;
        $('file-input').value = '';
        $('preview-img').removeAttribute('src');
        hide($('preview'));
        show($('dropzone-empty'));
        $('dropzone').classList.remove('has-file');
        $('btn-analyze').disabled = true;
        $('btn-reset').hidden = true;
        hide($('results'));
        clearAlert();
    }

    // --- Analisis con IA --------------------------------------------------

    var loaderTimer = null;

    function startLoader() {
        var sub = $('loader-sub');
        var i = 0;
        sub.textContent = LOADER_MESSAGES[0];
        show($('loader'));

        clearInterval(loaderTimer);
        loaderTimer = setInterval(function () {
            i = (i + 1) % LOADER_MESSAGES.length;
            sub.textContent = LOADER_MESSAGES[i];
        }, 2600);
    }

    function stopLoader() {
        clearInterval(loaderTimer);
        loaderTimer = null;
        hide($('loader'));
    }

    function analyze() {
        if (!state.file) {
            return;
        }

        clearAlert();
        hide($('results'));
        startLoader();
        $('btn-analyze').disabled = true;

        var form = new FormData();
        form.append('image', state.file);

        request('/recipes/generate-from-image', { method: 'POST', body: form })
            .then(function (data) {
                state.lastScan = data;
                renderScanResult(data);
            })
            .catch(function (error) {
                showAlert(error.message);
            })
            .then(function () {
                stopLoader();
                $('btn-analyze').disabled = false;
            });
    }

    // --- Demo instantanea --------------------------------------------------

    /**
     * Carga la foto de ejemplo en la vista previa y pide el resultado
     * precalculado. No consume cuota de Gemini y responde en milisegundos.
     */
    function runDemo() {
        clearAlert();
        hide($('results'));

        var button = $('btn-hero-demo');
        button.disabled = true;

        showDemoPreview();

        request('/recipes/demo')
            .then(function (data) {
                state.lastScan = data;
                state.demoLoaded = true;
                renderScanResult(data);
                toast('Resultado de ejemplo cargado al instante');
            })
            .catch(function (error) {
                showAlert('No se pudo cargar la demostracion: ' + error.message);
            })
            .then(function () {
                button.disabled = false;
            });
    }

    /** Muestra la foto demo en el dropzone, como si el usuario la hubiera subido. */
    function showDemoPreview() {
        state.file = null;
        $('preview-img').src = DEMO_IMAGE;
        $('preview-name').textContent = 'demo-fridge.jpg';
        $('preview-size').textContent = 'foto de ejemplo';
        hide($('dropzone-empty'));
        show($('preview'));
        $('dropzone').classList.add('has-file');
        // Sin fichero propio no hay nada que reanalizar: la demo ya trae resultado.
        $('btn-analyze').disabled = true;
        $('btn-reset').hidden = false;
    }

    /**
     * Lleva al usuario a la zona de carga. No se abre el selector de archivos
     * por codigo: tras un setTimeout se pierde el contexto de gesto y varios
     * navegadores bloquean el dialogo, asi que el usuario pulsa la zona.
     */
    function scrollToDropzone() {
        var dropzone = $('dropzone');
        dropzone.scrollIntoView({ behavior: 'smooth', block: 'center' });
        dropzone.focus({ preventScroll: true });
        dropzone.classList.add('is-highlighted');
        setTimeout(function () {
            dropzone.classList.remove('is-highlighted');
        }, 1400);
    }

    function renderScanResult(data) {
        var detected = data.detectedIngredients || [];
        $('detected-list').innerHTML = detected.length
            ? detected.map(function (name) {
                return '<li class="pill">' + escapeHtml(name) + '</li>';
            }).join('')
            : '<li class="pill">Sin ingredientes reconocidos</li>';

        $('recipe-cards').innerHTML = (data.recipes || []).map(recipeCard).join('');
        show($('results'));
        $('results').scrollIntoView({ behavior: 'smooth', block: 'start' });
    }

    // --- Plantillas ------------------------------------------------------

    var DIFFICULTY_LABEL = { FACIL: 'Facil', MEDIA: 'Media', DIFICIL: 'Dificil' };

    function recipeCard(recipe) {
        var difficulty = recipe.difficulty || 'MEDIA';
        var chips = [];

        if (recipe.prepTimeMinutes != null) {
            chips.push('<span class="chip">&#9201; ' + escapeHtml(recipe.prepTimeMinutes) + ' min</span>');
        }
        chips.push('<span class="chip chip--' + difficulty.toLowerCase() + '">&#9881; '
            + escapeHtml(DIFFICULTY_LABEL[difficulty] || difficulty) + '</span>');
        if (recipe.caloriesEstimate != null) {
            chips.push('<span class="chip">&#128293; ' + escapeHtml(recipe.caloriesEstimate) + ' kcal</span>');
        }

        var ingredients = (recipe.ingredients || []).map(function (ingredient) {
            var cssClass = ingredient.available ? 'ingredient--have' : 'ingredient--missing';
            var icon = ingredient.available ? '&#10004;' : '&#10008;';
            var quantity = ingredient.quantity
                ? ' <span class="ingredient__qty">' + escapeHtml(ingredient.quantity) + '</span>'
                : '';
            return '<li class="ingredient ' + cssClass + '">'
                + '<span class="ingredient__icon" aria-hidden="true">' + icon + '</span>'
                + '<span><span class="ingredient__name">' + escapeHtml(ingredient.name) + '</span>'
                + quantity + '</span></li>';
        }).join('');

        var steps = (recipe.steps || []).map(function (step) {
            return '<li>' + escapeHtml(step) + '</li>';
        }).join('');

        var isFavorite = recipe.favorite === true;

        // El tip solo se pinta si existe: las recetas anteriores a la Fase 2
        // no lo tienen y no deben mostrar un recuadro vacio.
        var tip = (recipe.chefTip && String(recipe.chefTip).trim())
            ? '<div class="cheftip">'
              + '<span class="cheftip__icon" aria-hidden="true">&#128161;</span>'
              + '<div class="cheftip__body">'
              + '<span class="cheftip__label">Tip del Chef</span>'
              + '<p class="cheftip__text">' + escapeHtml(recipe.chefTip) + '</p>'
              + '</div></div>'
            : '';

        return '<article class="card">'
            + '<h3 class="card__title">' + escapeHtml(recipe.title) + '</h3>'
            + (recipe.description
                ? '<p class="card__desc">' + escapeHtml(recipe.description) + '</p>' : '')
            + '<div class="card__meta">' + chips.join('') + '</div>'
            + (ingredients
                ? '<div class="card__block"><h4>Ingredientes</h4>'
                  + '<ul class="ingredients">' + ingredients + '</ul></div>' : '')
            + (steps
                ? '<div class="card__block"><h4>Preparacion</h4>'
                  + '<ol class="steps">' + steps + '</ol></div>' : '')
            + tip
            + '<div class="card__footer">'
            + '<button class="btn btn--fav' + (isFavorite ? ' is-on' : '') + '" type="button"'
            + ' data-favorite-id="' + escapeHtml(recipe.id) + '">'
            + (isFavorite ? '&#9733; Guardada en favoritos' : '&#9734; Guardar en Favoritos')
            + '</button></div>'
            + '</article>';
    }

    // --- Favoritos -------------------------------------------------------

    function toggleFavorite(recipeId, button) {
        if (!isLoggedIn()) {
            toast('Inicia sesion para guardar recetas');
            openModal('auth-modal');
            return;
        }

        button.disabled = true;
        jsonRequest('/recipes/' + recipeId + '/favorite', 'POST', {})
            .then(function (data) {
                button.classList.toggle('is-on', data.favorite);
                button.innerHTML = data.favorite
                    ? '&#9733; Guardada en favoritos'
                    : '&#9734; Guardar en Favoritos';
                toast(data.message);

                if ($('view-favorites').classList.contains('is-active')) {
                    loadFavorites();
                }
            })
            .catch(function (error) {
                toast(error.message);
            })
            .then(function () {
                button.disabled = false;
            });
    }

    function loadFavorites() {
        var container = $('favorite-cards');
        var empty = $('favorites-empty');

        if (!isLoggedIn()) {
            container.innerHTML = '';
            empty.textContent = 'Inicia sesion para ver tus recetas favoritas.';
            show(empty);
            return;
        }

        container.innerHTML = '';
        hide(empty);

        request('/recipes/favorites')
            .then(function (recipes) {
                if (!recipes || !recipes.length) {
                    empty.textContent = 'Todavia no has guardado ninguna receta.';
                    show(empty);
                    return;
                }
                container.innerHTML = recipes.map(recipeCard).join('');
            })
            .catch(function (error) {
                empty.textContent = error.message;
                show(empty);
            });
    }

    // --- Historial -------------------------------------------------------

    function loadHistory() {
        var container = $('history-list');
        var empty = $('history-empty');

        if (!isLoggedIn()) {
            container.innerHTML = '';
            empty.textContent = 'Inicia sesion para consultar tu historial.';
            show(empty);
            return;
        }

        container.innerHTML = '';
        hide(empty);

        request('/recipes/history')
            .then(function (sessions) {
                if (!sessions || !sessions.length) {
                    empty.textContent = 'Aun no tienes escaneos guardados en tu cuenta.';
                    show(empty);
                    return;
                }

                container.innerHTML = sessions.map(function (session) {
                    var pills = (session.detectedIngredients || []).map(function (name) {
                        return '<li class="pill">' + escapeHtml(name) + '</li>';
                    }).join('');

                    var titles = (session.recipes || []).map(function (recipe) {
                        return '<li>' + escapeHtml(recipe.title) + '</li>';
                    }).join('');

                    return '<article class="history-item">'
                        + '<div class="history-item__head">'
                        + '<span class="history-item__date">' + escapeHtml(formatDate(session.createdAt)) + '</span>'
                        + '<span class="history-item__count">'
                        + (session.recipes || []).length + ' recetas</span>'
                        + '</div>'
                        + '<ul class="pills">' + pills + '</ul>'
                        + '<ol class="history-item__titles">' + titles + '</ol>'
                        + '</article>';
                }).join('');
            })
            .catch(function (error) {
                empty.textContent = error.message;
                show(empty);
            });
    }

    // --- Modales ---------------------------------------------------------

    function openModal(id) {
        show($(id));
        if (id === 'docs-modal') {
            var frame = $('docs-frame');
            if (frame.getAttribute('src') !== '/swagger-ui.html') {
                frame.setAttribute('src', '/swagger-ui.html');
            }
        } else if (id === 'qr-modal') {
            renderQr();
        }
    }

    // --- Codigo QR ---------------------------------------------------------

    /** true si la direccion actual no es alcanzable desde otro dispositivo. */
    function isLocalAddress(hostname) {
        return hostname === 'localhost'
            || hostname === '127.0.0.1'
            || hostname === '::1'
            || hostname === ''
            || /^192\.168\./.test(hostname)
            || /^10\./.test(hostname)
            || /^172\.(1[6-9]|2\d|3[01])\./.test(hostname)
            || /\.local$/.test(hostname);
    }

    /**
     * Genera el QR con la URL viva del navegador. Si estamos en local, esa URL
     * no sirve en un telefono, asi que se codifica la direccion publica y se
     * avisa claramente de la sustitucion.
     */
    function renderQr() {
        var canvas = $('qr-canvas');
        var note = $('qr-note');

        var liveUrl = window.location.origin + window.location.pathname;
        var local = isLocalAddress(window.location.hostname);
        var target = local ? PRODUCTION_URL : liveUrl;

        $('qr-url').textContent = target;

        if (local) {
            note.innerHTML = 'Estas en <strong>' + escapeHtml(window.location.host) + '</strong>, '
                + 'una direccion que tu telefono no puede abrir. El codigo apunta a la '
                + 'version publicada.';
            show(note);
        } else {
            hide(note);
        }

        try {
            canvas.innerHTML = QR.svg(target, { ecc: 'M', border: 2 });
        } catch (error) {
            canvas.innerHTML = '';
            note.textContent = 'No se pudo generar el codigo QR: ' + error.message;
            show(note);
        }
    }

    function closeModal(id) {
        hide($(id));
    }

    function switchAuthTab(name) {
        document.querySelectorAll('.tab[data-tab]').forEach(function (tab) {
            tab.classList.toggle('is-active', tab.dataset.tab === name);
        });
        $('form-login').classList.toggle('is-active', name === 'login');
        $('form-register').classList.toggle('is-active', name === 'register');
    }

    function formError(form, message) {
        var el = form.querySelector('[data-form-error]');
        el.textContent = message || '';
        el.classList.toggle('is-hidden', !message);
    }

    function submitAuth(form, path, buildBody, successMessage) {
        return function (event) {
            event.preventDefault();
            formError(form, '');

            var data = new FormData(form);
            var button = form.querySelector('button[type="submit"]');
            button.disabled = true;

            jsonRequest(path, 'POST', buildBody(data))
                .then(function (auth) {
                    applySession(auth);
                    closeModal('auth-modal');
                    form.reset();
                    toast(successMessage);
                })
                .catch(function (error) {
                    formError(form, error.message);
                })
                .then(function () {
                    button.disabled = false;
                });
        };
    }

    // --- Enlace de eventos -----------------------------------------------

    function bindEvents() {
        var dropzone = $('dropzone');
        var fileInput = $('file-input');

        dropzone.addEventListener('click', function (event) {
            if (!dropzone.classList.contains('has-file') && event.target !== fileInput) {
                fileInput.click();
            }
        });

        dropzone.addEventListener('keydown', function (event) {
            if ((event.key === 'Enter' || event.key === ' ')
                && !dropzone.classList.contains('has-file')) {
                event.preventDefault();
                fileInput.click();
            }
        });

        ['dragenter', 'dragover'].forEach(function (type) {
            dropzone.addEventListener(type, function (event) {
                event.preventDefault();
                dropzone.classList.add('is-dragover');
            });
        });

        ['dragleave', 'dragend'].forEach(function (type) {
            dropzone.addEventListener(type, function () {
                dropzone.classList.remove('is-dragover');
            });
        });

        dropzone.addEventListener('drop', function (event) {
            event.preventDefault();
            dropzone.classList.remove('is-dragover');
            if (event.dataTransfer.files && event.dataTransfer.files.length) {
                handleFile(event.dataTransfer.files[0]);
            }
        });

        fileInput.addEventListener('change', function () {
            if (fileInput.files.length) {
                handleFile(fileInput.files[0]);
            }
        });

        $('btn-analyze').addEventListener('click', analyze);
        $('btn-reset').addEventListener('click', resetFile);
        $('btn-docs').addEventListener('click', function () {
            openModal('docs-modal');
        });

        // Botones del hero
        $('btn-hero-upload').addEventListener('click', scrollToDropzone);
        $('btn-hero-demo').addEventListener('click', runDemo);
        $('btn-hero-qr').addEventListener('click', function () {
            openModal('qr-modal');
        });

        $('btn-auth').addEventListener('click', function () {
            if (isLoggedIn()) {
                logout(false);
            } else {
                switchAuthTab('login');
                openModal('auth-modal');
            }
        });

        document.querySelectorAll('.navlink[data-view]').forEach(function (link) {
            link.addEventListener('click', function () {
                switchView(link.dataset.view);
            });
        });

        document.querySelectorAll('.tab[data-tab]').forEach(function (tab) {
            tab.addEventListener('click', function () {
                switchAuthTab(tab.dataset.tab);
            });
        });

        document.querySelectorAll('[data-close-modal]').forEach(function (element) {
            element.addEventListener('click', function () {
                closeModal(element.closest('.modal').id);
            });
        });

        document.addEventListener('keydown', function (event) {
            if (event.key === 'Escape') {
                closeModal('auth-modal');
                closeModal('docs-modal');
                closeModal('qr-modal');
            }
        });

        // Delegacion para los botones de favorito generados dinamicamente.
        document.addEventListener('click', function (event) {
            var button = event.target.closest('[data-favorite-id]');
            if (button) {
                toggleFavorite(button.dataset.favoriteId, button);
            }
        });

        $('form-login').addEventListener('submit', submitAuth(
            $('form-login'),
            '/auth/login',
            function (data) {
                return {
                    username: data.get('username'),
                    password: data.get('password')
                };
            },
            'Bienvenido de nuevo'));

        $('form-register').addEventListener('submit', submitAuth(
            $('form-register'),
            '/auth/register',
            function (data) {
                return {
                    username: data.get('username'),
                    email: data.get('email'),
                    password: data.get('password'),
                    fullName: data.get('fullName') || null
                };
            },
            'Cuenta creada correctamente'));
    }

    // --- Arranque --------------------------------------------------------

    /**
     * El boton de QR solo tiene sentido en escritorio: si ya estas en el movil,
     * no necesitas escanear nada para abrir la web aqui.
     */
    function isHandheld() {
        return window.matchMedia('(max-width: 767px), (pointer: coarse)').matches;
    }

    document.addEventListener('DOMContentLoaded', function () {
        bindEvents();
        renderSession();

        if (!isHandheld()) {
            $('btn-hero-qr').hidden = false;
        }
    });
})();
