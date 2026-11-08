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
        lastScan: null,
        showingDemo: false,
        detected: [],
        excluded: []
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
            var authTxt = $('btn-auth-text');
            if (authTxt) { authTxt.textContent = 'Salir'; } else { button.textContent = 'Salir'; }
        } else {
            hide(chip);
            var authTxt = $('btn-auth-text');
            if (authTxt) { authTxt.textContent = 'Iniciar sesion'; } else { button.textContent = 'Iniciar sesion'; }
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
        document.querySelectorAll('[data-dock-view]').forEach(function (btn) {
            btn.classList.toggle('is-active', btn.getAttribute('data-dock-view') === name);
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
                renderScanResult(data, { demo: true });
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

    /**
     * Pinta ingredientes y recetas.
     *
     * @param data     respuesta del backend.
     * @param options  demo: marca el resultado como ejemplo precalculado.
     *                 scroll: lleva la vista a los resultados. Se desactiva
     *                 en la carga automatica, porque mover la pagina sola
     *                 nada mas abrirla desorienta.
     */
    function renderScanResult(data, options) {
        var opts = options || {};
        var detected = data.detectedIngredients || [];

        // Un resultado nuevo empieza con todos los ingredientes dentro.
        state.detected = detected.slice();
        state.excluded = [];

        $('detected-list').innerHTML = detected.length
            ? detected.map(function (name) {
                return '<li><button class="pill" type="button" aria-pressed="true"'
                    + ' data-ingredient="' + escapeHtml(name) + '">'
                    + '<span class="pill__mark" aria-hidden="true"></span>'
                    + escapeHtml(name) + '</button></li>';
            }).join('')
            : '<li><span class="pill pill--static">Sin ingredientes reconocidos</span></li>';

        syncExcludedBar();

        $('recipe-cards').innerHTML = (data.recipes || []).map(recipeCard).join('');

        state.showingDemo = opts.demo === true;
        $('results-demo').classList.toggle('is-hidden', !state.showingDemo);

        show($('results'));

        if (opts.scroll !== false) {
            $('results').scrollIntoView({ behavior: 'smooth', block: 'start' });
        }
    }

    // --- Descartar ingredientes y rehacer ---------------------------------

    /**
     * Marca o desmarca un ingrediente. Solo cambia el estado: rehacer las
     * recetas es una llamada a Gemini y cuesta cuota, asi que no se dispara
     * sola al pulsar, la pide el usuario con el boton de la barra.
     */
    function toggleIngredient(button) {
        var name = button.dataset.ingredient;
        var index = state.excluded.indexOf(name);

        if (index === -1) {
            state.excluded.push(name);
        } else {
            state.excluded.splice(index, 1);
        }

        var off = state.excluded.indexOf(name) !== -1;
        button.classList.toggle('is-off', off);
        button.setAttribute('aria-pressed', off ? 'false' : 'true');

        syncExcludedBar();
    }

    /** Devuelve todos los ingredientes al estado activo. */
    function restoreIngredients() {
        state.excluded = [];
        document.querySelectorAll('#detected-list .pill').forEach(function (pill) {
            pill.classList.remove('is-off');
            pill.setAttribute('aria-pressed', 'true');
        });
        syncExcludedBar();
    }

    /** Muestra u oculta la barra de descartados y actualiza su recuento. */
    function syncExcludedBar() {
        var bar = $('excluded-bar');
        var count = state.excluded.length;
        var kept = state.detected.length - count;

        bar.classList.toggle('is-hidden', count === 0);

        if (count === 0) {
            return;
        }

        $('excluded-count').textContent = count === 1
            ? '1 ingrediente descartado'
            : count + ' ingredientes descartados';

        // Sin ingredientes no hay nada que cocinar, y el backend lo rechaza.
        $('btn-regenerate').disabled = kept === 0;
        $('excluded-hint').textContent = kept === 0
            ? 'Deja al menos uno para poder rehacer.'
            : 'Se rehara con los ' + kept + ' restantes. Usa una consulta a Gemini.';
    }

    /**
     * Pide 3 recetas nuevas con los ingredientes que quedan. Es una llamada
     * real al modelo, no un filtrado local: por eso reutiliza el loader con
     * esqueletos y deja el resultado como un escaneo normal, no como demo.
     */
    function regenerate() {
        var kept = state.detected.filter(function (name) {
            return state.excluded.indexOf(name) === -1;
        });

        if (!kept.length) {
            return;
        }

        clearAlert();
        hide($('results'));
        startLoader();
        $('btn-regenerate').disabled = true;

        jsonRequest('/recipes/from-ingredients', 'POST', { ingredients: kept })
            .then(function (data) {
                state.lastScan = data;
                renderScanResult(data);
                toast('Recetas rehechas sin los ingredientes descartados');
            })
            .catch(function (error) {
                showAlert('No se pudieron rehacer las recetas: ' + error.message);
                show($('results'));
            })
            .then(function () {
                stopLoader();
                $('btn-regenerate').disabled = false;
            });
    }

    /**
     * Carga la sesion demo al abrir la pagina para que no se vea vacia.
     * Sale del endpoint sembrado, asi que no gasta cuota de Gemini. Si falla
     * no se avisa: es contenido de relleno, no algo que el usuario pidio.
     */
    function autoLoadDemo() {
        request('/recipes/demo')
            .then(function (data) {
                // Si el usuario ya subio una foto mientras tanto, no la pisamos.
                if (state.lastScan) {
                    return;
                }
                state.lastScan = data;
                renderScanResult(data, { demo: true, scroll: false });
            })
            .catch(function () {
                /* Sin demo la pagina sigue siendo usable. */
            });
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
            return '<li class="text-animate-item">' + escapeHtml(step) + '</li>';
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

        // Cuantos ingredientes de la receta ya tienes. Es el dato que decide
        // si la cocinas hoy o tienes que bajar a comprar, asi que va arriba.
        var all = recipe.ingredients || [];
        var have = all.filter(function (ingredient) { return ingredient.available; }).length;
        var match = all.length
            ? '<p class="card__match">'
              + '<span class="card__match-bar" aria-hidden="true">'
              + '<span style="width: ' + Math.round((have / all.length) * 100) + '%"></span>'
              + '</span>'
              + '<span class="card__match-text">Tienes <b>' + have + '</b> de '
              + all.length + ' ingredientes</span></p>'
            : '';

        return '<article class="card text-animate-card">'
            + '<header class="card__head card__head--' + difficulty.toLowerCase() + '">'
            + '<h3 class="card__title">' + escapeHtml(recipe.title) + '</h3>'
            + '<div class="card__meta">' + chips.join('') + '</div>'
            + '</header>'
            + '<div class="card__body">'
            + (recipe.description
                ? '<p class="card__desc">' + escapeHtml(recipe.description) + '</p>' : '')
            + match
            + (ingredients
                ? '<div class="card__block"><h4>Ingredientes</h4>'
                  + '<ul class="ingredients">' + ingredients + '</ul></div>' : '')
            + (steps
                ? '<div class="card__block"><h4>Preparacion</h4>'
                  + '<ol class="steps">' + steps + '</ol></div>' : '')
            + tip
            + '</div>'
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

    /**
     * Rellena un estado vacio. El boton cambia de papel segun el caso:
     * sin sesion lleva al login, con sesion lleva a escanear. Antes la
     * vista invitaba a escanear a quien ni siquiera podia guardar nada.
     *
     * @param prefix  "favorites" o "history".
     * @param action  "login" o "scan".
     */
    function setEmpty(prefix, title, text, action) {
        $(prefix + '-empty-title').textContent = title;
        $(prefix + '-empty-text').textContent = text;

        var button = $(prefix + '-empty-action');
        button.textContent = action === 'login' ? 'Iniciar sesion' : 'Escanear una foto';
        button.dataset.action = action;

        show($(prefix + '-empty'));
    }

    function loadFavorites() {
        var container = $('favorite-cards');
        var empty = $('favorites-empty');

        if (!isLoggedIn()) {
            container.innerHTML = '';
            setEmpty('favorites', 'Necesitas una cuenta',
                'Los favoritos se guardan en tu cuenta para que los tengas en cualquier dispositivo.',
                'login');
            return;
        }

        container.innerHTML = '';
        hide(empty);

        request('/recipes/favorites')
            .then(function (recipes) {
                if (!recipes || !recipes.length) {
                    setEmpty('favorites', 'Sin favoritos todavia',
                        'Pulsa la estrella de cualquier receta y la encontraras aqui.',
                        'scan');
                    return;
                }
                container.innerHTML = recipes.map(recipeCard).join('');
            })
            .catch(function (error) {
                setEmpty('favorites', 'No se pudieron cargar', error.message, 'scan');
            });
    }

    // --- Historial -------------------------------------------------------

    function loadHistory() {
        var container = $('history-list');
        var empty = $('history-empty');

        if (!isLoggedIn()) {
            container.innerHTML = '';
            setEmpty('history', 'Necesitas una cuenta',
                'El historial se asocia a tu cuenta: sin ella los escaneos no se guardan.',
                'login');
            return;
        }

        container.innerHTML = '';
        hide(empty);

        request('/recipes/history')
            .then(function (sessions) {
                if (!sessions || !sessions.length) {
                    setEmpty('history', 'Sin escaneos todavia',
                        'Analiza tu primera foto y quedara guardada aqui con su resultado completo.',
                        'scan');
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
                setEmpty('history', 'No se pudo cargar', error.message, 'scan');
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

        // Modo de Navegacion (Barra vs Dock)
        function setNavMode(mode) {
            document.body.setAttribute('data-nav-mode', mode);
            try { localStorage.setItem('smartchef-app-nav-mode', mode); } catch (e) {}
        }
        var savedNav = 'topbar';
        try { savedNav = localStorage.getItem('smartchef-app-nav-mode') || 'topbar'; } catch (e) {}
        setNavMode(savedNav);

        var btnToDock = $('btn-switch-to-dock');
        if (btnToDock) btnToDock.addEventListener('click', function () { setNavMode('dock'); });
        var btnToTop = $('btn-switch-to-topbar');
        if (btnToTop) btnToTop.addEventListener('click', function () { setNavMode('topbar'); });

        // Eventos del Dock
        document.querySelectorAll('[data-dock-view]').forEach(function (btn) {
            btn.addEventListener('click', function () {
                switchView(btn.getAttribute('data-dock-view'));
            });
        });

        var dockDocs = $('dock-btn-docs');
        if (dockDocs) dockDocs.addEventListener('click', function () {
            var btnDocs = $('btn-docs');
            if (btnDocs) btnDocs.click();
        });

        var dockAuth = $('dock-btn-auth');
        if (dockAuth) dockAuth.addEventListener('click', function () {
            var btnAuth = $('btn-auth');
            if (btnAuth) btnAuth.click();
        });

        // Theme Switcher con GPU Native 60fps View Transition
        var themeBtn = $('theme-btn');
        var dockThemeBtn = $('dock-theme-toggle');
        var themeIcon = $('theme-icon');

        function applyTheme(target) {
            document.documentElement.setAttribute('data-theme', target);
            try { localStorage.setItem('smartchef-theme', target); } catch (e) {}
            var icon = target === 'dark' ? '☀️' : '🌙';
            if (themeIcon) themeIcon.textContent = icon;
            if (dockThemeBtn) dockThemeBtn.textContent = icon;
        }

        function toggleTheme(e) {
            var cur = document.documentElement.getAttribute('data-theme') || 'light';
            var next = cur === 'light' ? 'dark' : 'light';
            if (!document.startViewTransition || window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
                applyTheme(next);
                return;
            }
            var rect = e && e.currentTarget ? e.currentTarget.getBoundingClientRect() : null;
            var x = e && e.clientX ? e.clientX : (rect ? rect.left + rect.width / 2 : window.innerWidth / 2);
            var y = e && e.clientY ? e.clientY : (rect ? rect.top + rect.height / 2 : window.innerHeight / 2);
            var radius = Math.hypot(Math.max(x, window.innerWidth - x), Math.max(y, window.innerHeight - y));
            document.documentElement.style.setProperty('--reveal-x', x + 'px');
            document.documentElement.style.setProperty('--reveal-y', y + 'px');
            document.documentElement.style.setProperty('--reveal-radius', radius + 'px');
            document.startViewTransition(function () {
                applyTheme(next);
            });
        }

        var savedTheme = 'light';
        try { savedTheme = localStorage.getItem('smartchef-theme') || 'light'; } catch (e) {}
        applyTheme(savedTheme);

        if (themeBtn) themeBtn.addEventListener('click', toggleTheme);
        if (dockThemeBtn) dockThemeBtn.addEventListener('click', toggleTheme);

        // Dock magnification
        var dockContainer = $('floating-dock');
        if (dockContainer) {
            var dockBtns = dockContainer.querySelectorAll('.dock-icon-btn');
            dockBtns.forEach(function (btn, idx) {
                btn.addEventListener('mouseenter', function () {
                    if (dockBtns[idx - 1]) dockBtns[idx - 1].style.transform = 'scale(1.15) translateY(-4px)';
                    if (dockBtns[idx + 1]) dockBtns[idx + 1].style.transform = 'scale(1.15) translateY(-4px)';
                });
                btn.addEventListener('mouseleave', function () {
                    if (dockBtns[idx - 1]) dockBtns[idx - 1].style.transform = '';
                    if (dockBtns[idx + 1]) dockBtns[idx + 1].style.transform = '';
                });
            });
        }
    }

    // --- Arranque --------------------------------------------------------

    /**
     * El boton de QR solo tiene sentido en escritorio: si ya estas en el movil,
     * no necesitas escanear nada para abrir la web aqui.
     */
    var handheldQuery = window.matchMedia('(max-width: 767px), (pointer: coarse)');

    function isHandheld() {
        return handheldQuery.matches;
    }

    /**
     * Mantiene el boton de QR acorde al dispositivo. Se reevalua al cambiar el
     * tamano o la orientacion: decidirlo solo al cargar deja el boton oculto
     * para siempre en una ventana que empieza estrecha y luego se agranda.
     */
    function syncQrButton() {
        $('btn-hero-qr').hidden = isHandheld();
    }

    /**
     * Inclinacion 3D y brillo que siguen al puntero, el mismo efecto que usa
     * .glare-card en el landing. Solo con puntero fino y si el usuario no ha
     * pedido menos movimiento: en tactil no hay puntero al que seguir.
     */
    function bindGlareCards() {
        if (!window.matchMedia('(hover: hover)').matches
            || window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
            return;
        }

        document.querySelectorAll('.glare-card').forEach(function (card) {
            card.addEventListener('mousemove', function (event) {
                var rect = card.getBoundingClientRect();
                var x = event.clientX - rect.left;
                var y = event.clientY - rect.top;
                var rotateX = ((y - rect.height / 2) / (rect.height / 2)) * -6;
                var rotateY = ((x - rect.width / 2) / (rect.width / 2)) * 6;

                card.style.transform = 'perspective(1000px) rotateX(' + rotateX.toFixed(2)
                    + 'deg) rotateY(' + rotateY.toFixed(2) + 'deg)';
                card.style.setProperty('--gx', ((x / rect.width) * 100).toFixed(1) + '%');
                card.style.setProperty('--gy', ((y / rect.height) * 100).toFixed(1) + '%');
            });

            card.addEventListener('mouseleave', function () {
                card.style.transform = '';
            });
        });
    }

    /**
     * Las pildoras se pintan cada vez que llega un resultado, asi que el
     * listener va en el contenedor y no en cada una.
     */
    function bindIngredientPills() {
        $('detected-list').addEventListener('click', function (event) {
            var pill = event.target.closest('.pill[data-ingredient]');
            if (pill) {
                toggleIngredient(pill);
            }
        });

        $('btn-regenerate').addEventListener('click', regenerate);
        $('btn-restore-ingredients').addEventListener('click', restoreIngredients);
    }

    /** El boton de cada estado vacio hace una cosa u otra segun setEmpty. */
    function bindEmptyActions() {
        ['favorites', 'history'].forEach(function (name) {
            var button = $(name + '-empty-action');
            button.addEventListener('click', function () {
                if (button.dataset.action === 'login') {
                    openModal('auth-modal');
                } else {
                    switchView('scan');
                }
            });
        });
    }

    document.addEventListener('DOMContentLoaded', function () {
        bindEvents();
        bindEmptyActions();
        bindIngredientPills();
        renderSession();
        bindGlareCards();
        syncQrButton();
        autoLoadDemo();

        if (handheldQuery.addEventListener) {
            handheldQuery.addEventListener('change', syncQrButton);
        } else {
            // Safari antiguo no implementa addEventListener en MediaQueryList.
            handheldQuery.addListener(syncQrButton);
        }
    });
})();
