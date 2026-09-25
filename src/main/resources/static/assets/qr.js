/*!
 * qr.js - Generador de codigos QR autonomo (modo byte, niveles L/M/Q/H).
 *
 * Implementacion local y sin dependencias ni llamadas de red: la SPA debe poder
 * generar el QR aunque no haya internet ni servicios externos disponibles.
 * Sigue la norma ISO/IEC 18004 y produce SVG vectorial.
 *
 * API:
 *   QR.svg(texto, { ecc: 'M', border: 4, scale: 1, dark: '#111', light: '#fff' })
 *   QR.matrix(texto, 'M')  -> array 2D de booleanos (true = modulo oscuro)
 */
(function (global) {
    'use strict';

    // Correccion de errores: bits de formato por nivel.
    var ECC = {
        L: { bits: 1, idx: 0 },
        M: { bits: 0, idx: 1 },
        Q: { bits: 3, idx: 2 },
        H: { bits: 2, idx: 3 }
    };

    // Palabras de correccion por bloque, por nivel y version (1..40).
    var ECC_CODEWORDS_PER_BLOCK = [
        // L
        [7, 10, 15, 20, 26, 18, 20, 24, 30, 18, 20, 24, 26, 30, 22, 24, 28, 30, 28, 28,
            28, 28, 30, 30, 26, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30],
        // M
        [10, 16, 26, 18, 24, 16, 18, 22, 22, 26, 30, 22, 22, 24, 24, 28, 28, 26, 26, 26,
            26, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28],
        // Q
        [13, 22, 18, 26, 18, 24, 18, 22, 20, 24, 28, 26, 24, 20, 30, 24, 28, 28, 26, 30,
            28, 30, 30, 30, 30, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30],
        // H
        [17, 28, 22, 16, 22, 28, 26, 26, 24, 28, 24, 28, 22, 24, 24, 30, 28, 28, 26, 28,
            30, 24, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30]
    ];

    // Numero de bloques de correccion, por nivel y version (1..40).
    var NUM_ERROR_CORRECTION_BLOCKS = [
        [1, 1, 1, 1, 1, 2, 2, 2, 2, 4, 4, 4, 4, 4, 6, 6, 6, 6, 7, 8,
            8, 9, 9, 10, 12, 12, 12, 13, 14, 15, 16, 17, 18, 19, 19, 20, 21, 22, 24, 25],
        [1, 1, 1, 2, 2, 4, 4, 4, 5, 5, 5, 8, 9, 9, 10, 10, 11, 13, 14, 16,
            17, 17, 18, 20, 21, 23, 25, 26, 28, 29, 31, 33, 35, 37, 38, 40, 43, 45, 47, 49],
        [1, 1, 2, 2, 4, 4, 6, 6, 8, 8, 8, 10, 12, 16, 12, 17, 16, 18, 21, 20,
            23, 23, 25, 27, 29, 34, 34, 35, 38, 40, 43, 45, 48, 51, 53, 56, 59, 62, 65, 68],
        [1, 1, 2, 4, 4, 4, 5, 6, 8, 8, 11, 11, 16, 16, 18, 16, 19, 21, 25, 25,
            25, 34, 30, 32, 35, 37, 40, 42, 45, 48, 51, 54, 57, 60, 63, 66, 70, 74, 77, 81]
    ];

    // --- Aritmetica en GF(256) con polinomio primitivo 0x11D ---------------

    function gfMultiply(x, y) {
        var z = 0;
        for (var i = 7; i >= 0; i--) {
            z = (z << 1) ^ ((z >>> 7) * 0x11D);
            z ^= ((y >>> i) & 1) * x;
        }
        return z & 0xFF;
    }

    /** Divisor de Reed-Solomon de grado `degree`. */
    function rsDivisor(degree) {
        var result = [];
        for (var i = 0; i < degree - 1; i++) {
            result.push(0);
        }
        result.push(1);

        var root = 1;
        for (var i2 = 0; i2 < degree; i2++) {
            for (var j = 0; j < result.length; j++) {
                result[j] = gfMultiply(result[j], root);
                if (j + 1 < result.length) {
                    result[j] ^= result[j + 1];
                }
            }
            root = gfMultiply(root, 0x02);
        }
        return result;
    }

    function rsRemainder(data, divisor) {
        var result = divisor.map(function () { return 0; });

        data.forEach(function (b) {
            var factor = b ^ result.shift();
            result.push(0);
            divisor.forEach(function (d, i) {
                result[i] ^= gfMultiply(d, factor);
            });
        });
        return result;
    }

    // --- Capacidad -----------------------------------------------------------

    /** Modulos disponibles para datos (incluye bits sobrantes). */
    function numRawDataModules(ver) {
        var result = (16 * ver + 128) * ver + 64;
        if (ver >= 2) {
            var numAlign = Math.floor(ver / 7) + 2;
            result -= (25 * numAlign - 10) * numAlign - 55;
            if (ver >= 7) {
                result -= 36;
            }
        }
        return result;
    }

    function numDataCodewords(ver, eccIdx) {
        return Math.floor(numRawDataModules(ver) / 8)
            - ECC_CODEWORDS_PER_BLOCK[eccIdx][ver - 1]
            * NUM_ERROR_CORRECTION_BLOCKS[eccIdx][ver - 1];
    }

    function charCountBits(ver) {
        // Modo byte: 8 bits hasta la version 9, 16 a partir de la 10.
        return ver <= 9 ? 8 : 16;
    }

    // --- Codificacion --------------------------------------------------------

    function toUtf8Bytes(str) {
        var out = [];
        var encoded = encodeURIComponent(str);
        for (var i = 0; i < encoded.length; i++) {
            if (encoded.charAt(i) === '%') {
                out.push(parseInt(encoded.substr(i + 1, 2), 16));
                i += 2;
            } else {
                out.push(encoded.charCodeAt(i));
            }
        }
        return out;
    }

    function appendBits(bits, value, length) {
        for (var i = length - 1; i >= 0; i--) {
            bits.push((value >>> i) & 1);
        }
    }

    /** Elige la version mas pequena en la que caben los datos. */
    function chooseVersion(dataLen, eccIdx) {
        for (var ver = 1; ver <= 40; ver++) {
            var capacityBits = numDataCodewords(ver, eccIdx) * 8;
            var usedBits = 4 + charCountBits(ver) + dataLen * 8;
            if (usedBits <= capacityBits) {
                return ver;
            }
        }
        throw new Error('El texto es demasiado largo para un codigo QR');
    }

    function buildCodewords(bytes, ver, eccIdx) {
        var capacityBits = numDataCodewords(ver, eccIdx) * 8;
        var bits = [];

        appendBits(bits, 0x4, 4);                     // Indicador de modo byte
        appendBits(bits, bytes.length, charCountBits(ver));
        bytes.forEach(function (b) { appendBits(bits, b, 8); });

        // Terminador y relleno hasta completar bytes.
        appendBits(bits, 0, Math.min(4, capacityBits - bits.length));
        appendBits(bits, 0, (8 - bits.length % 8) % 8);

        for (var pad = 0xEC; bits.length < capacityBits; pad ^= 0xEC ^ 0x11) {
            appendBits(bits, pad, 8);
        }

        var dataCodewords = [];
        for (var i = 0; i < bits.length; i += 8) {
            var byteVal = 0;
            for (var j = 0; j < 8; j++) {
                byteVal = (byteVal << 1) | bits[i + j];
            }
            dataCodewords.push(byteVal);
        }
        return dataCodewords;
    }

    /** Divide en bloques, calcula la correccion e intercala el resultado. */
    function addEccAndInterleave(data, ver, eccIdx) {
        var numBlocks = NUM_ERROR_CORRECTION_BLOCKS[eccIdx][ver - 1];
        var blockEccLen = ECC_CODEWORDS_PER_BLOCK[eccIdx][ver - 1];
        var rawCodewords = Math.floor(numRawDataModules(ver) / 8);
        var numShortBlocks = numBlocks - rawCodewords % numBlocks;
        var shortBlockLen = Math.floor(rawCodewords / numBlocks);

        var blocks = [];
        var divisor = rsDivisor(blockEccLen);
        // Todos los bloques se rellenan a la misma longitud con la correccion al
        // final. El hueco de relleno de los bloques cortos es el que se salta al
        // intercalar; sin el, el salto se comeria la primera palabra de ECC.
        var paddedDataLen = shortBlockLen + 1 - blockEccLen;

        for (var i = 0, k = 0; i < numBlocks; i++) {
            var datLen = shortBlockLen - blockEccLen + (i < numShortBlocks ? 0 : 1);
            var dat = data.slice(k, k + datLen);
            k += datLen;

            var block = dat.slice();
            while (block.length < paddedDataLen) {
                block.push(0);
            }
            blocks.push(block.concat(rsRemainder(dat, divisor)));
        }

        var result = [];
        for (var col = 0; col < blocks[0].length; col++) {
            for (var b = 0; b < blocks.length; b++) {
                // Se omite el relleno que los bloques cortos no usan.
                if (col !== shortBlockLen - blockEccLen || b >= numShortBlocks) {
                    result.push(blocks[b][col]);
                }
            }
        }
        return result;
    }

    // --- Dibujo de la matriz -------------------------------------------------

    function Matrix(size) {
        this.size = size;
        this.modules = [];
        this.isFunction = [];
        for (var y = 0; y < size; y++) {
            this.modules.push(new Array(size).fill(false));
            this.isFunction.push(new Array(size).fill(false));
        }
    }

    Matrix.prototype.set = function (x, y, dark, isFn) {
        this.modules[y][x] = dark;
        if (isFn) {
            this.isFunction[y][x] = true;
        }
    };

    function alignmentPositions(ver) {
        if (ver === 1) {
            return [];
        }
        var numAlign = Math.floor(ver / 7) + 2;
        var step = (ver === 32)
            ? 26
            : Math.ceil((ver * 4 + 4) / (numAlign * 2 - 2)) * 2;

        var result = [6];
        for (var pos = ver * 4 + 10; result.length < numAlign; pos -= step) {
            result.splice(1, 0, pos);
        }
        return result;
    }

    function drawFinder(m, cx, cy) {
        for (var dy = -4; dy <= 4; dy++) {
            for (var dx = -4; dx <= 4; dx++) {
                var dist = Math.max(Math.abs(dx), Math.abs(dy));
                var x = cx + dx;
                var y = cy + dy;
                if (x >= 0 && x < m.size && y >= 0 && y < m.size) {
                    m.set(x, y, dist !== 2 && dist !== 4, true);
                }
            }
        }
    }

    function drawAlignment(m, cx, cy) {
        for (var dy = -2; dy <= 2; dy++) {
            for (var dx = -2; dx <= 2; dx++) {
                m.set(cx + dx, cy + dy, Math.max(Math.abs(dx), Math.abs(dy)) !== 1, true);
            }
        }
    }

    function drawFunctionPatterns(m, ver) {
        var size = m.size;

        // Patrones de tiempo
        for (var i = 0; i < size; i++) {
            m.set(6, i, i % 2 === 0, true);
            m.set(i, 6, i % 2 === 0, true);
        }

        drawFinder(m, 3, 3);
        drawFinder(m, size - 4, 3);
        drawFinder(m, 3, size - 4);

        var align = alignmentPositions(ver);
        for (var a = 0; a < align.length; a++) {
            for (var b = 0; b < align.length; b++) {
                // Las esquinas chocan con los patrones de busqueda.
                var skip = (a === 0 && b === 0)
                    || (a === 0 && b === align.length - 1)
                    || (a === align.length - 1 && b === 0);
                if (!skip) {
                    drawAlignment(m, align[a], align[b]);
                }
            }
        }

        // Areas reservadas (se rellenan luego con formato y version).
        drawFormatBits(m, 0, 0, true);
        drawVersion(m, ver);
    }

    /** Informacion de formato con BCH(15,5); reservado=true solo marca el area. */
    function drawFormatBits(m, eccBits, mask, reserveOnly) {
        var size = m.size;
        var data = (eccBits << 3) | mask;
        var rem = data;
        for (var i = 0; i < 10; i++) {
            rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
        }
        var bits = ((data << 10) | rem) ^ 0x5412;

        function bit(n) {
            return reserveOnly ? false : ((bits >>> n) & 1) === 1;
        }

        // Copia junto al patron superior izquierdo
        for (var j = 0; j <= 5; j++) {
            m.set(8, j, bit(j), true);
        }
        m.set(8, 7, bit(6), true);
        m.set(8, 8, bit(7), true);
        m.set(7, 8, bit(8), true);
        for (var k = 9; k < 15; k++) {
            m.set(14 - k, 8, bit(k), true);
        }

        // Segunda copia, repartida en las otras dos esquinas
        for (var p = 0; p < 8; p++) {
            m.set(size - 1 - p, 8, bit(p), true);
        }
        for (var q = 8; q < 15; q++) {
            m.set(8, size - 15 + q, bit(q), true);
        }
        m.set(8, size - 8, true, true); // Modulo oscuro siempre presente
    }

    /** Informacion de version (solo versiones 7 en adelante). */
    function drawVersion(m, ver) {
        if (ver < 7) {
            return;
        }
        var size = m.size;
        var rem = ver;
        for (var i = 0; i < 12; i++) {
            rem = (rem << 1) ^ ((rem >>> 11) * 0x1F25);
        }
        var bits = (ver << 12) | rem;

        for (var j = 0; j < 18; j++) {
            var dark = ((bits >>> j) & 1) === 1;
            var a = size - 11 + j % 3;
            var b = Math.floor(j / 3);
            m.set(a, b, dark, true);
            m.set(b, a, dark, true);
        }
    }

    function drawCodewords(m, codewords) {
        var size = m.size;
        var i = 0;

        for (var right = size - 1; right >= 1; right -= 2) {
            if (right === 6) {
                right = 5; // La columna 6 es patron de tiempo
            }
            for (var vert = 0; vert < size; vert++) {
                for (var j = 0; j < 2; j++) {
                    var x = right - j;
                    var upward = ((right + 1) & 2) === 0;
                    var y = upward ? size - 1 - vert : vert;

                    if (!m.isFunction[y][x] && i < codewords.length * 8) {
                        m.modules[y][x] = ((codewords[i >>> 3] >>> (7 - (i & 7))) & 1) === 1;
                        i++;
                    }
                    // Los bits sobrantes quedan claros, como exige la norma.
                }
            }
        }
    }

    function maskCondition(mask, x, y) {
        switch (mask) {
            case 0: return (x + y) % 2 === 0;
            case 1: return y % 2 === 0;
            case 2: return x % 3 === 0;
            case 3: return (x + y) % 3 === 0;
            case 4: return (Math.floor(x / 3) + Math.floor(y / 2)) % 2 === 0;
            case 5: return x * y % 2 + x * y % 3 === 0;
            case 6: return (x * y % 2 + x * y % 3) % 2 === 0;
            case 7: return ((x + y) % 2 + x * y % 3) % 2 === 0;
            default: throw new Error('Mascara invalida');
        }
    }

    function applyMask(m, mask) {
        for (var y = 0; y < m.size; y++) {
            for (var x = 0; x < m.size; x++) {
                if (!m.isFunction[y][x] && maskCondition(mask, x, y)) {
                    m.modules[y][x] = !m.modules[y][x];
                }
            }
        }
    }

    /** Puntuacion de penalizacion de la norma; menor es mejor. */
    function penaltyScore(m) {
        var size = m.size;
        var result = 0;
        var dark = 0;

        for (var y = 0; y < size; y++) {
            for (var x = 0; x < size; x++) {
                if (m.modules[y][x]) {
                    dark++;
                }
            }
        }

        // Regla 1: series de 5 o mas modulos iguales (filas y columnas)
        for (var i = 0; i < size; i++) {
            var runColor = false;
            var runLen = 0;
            for (var j = 0; j < size; j++) {
                if (m.modules[i][j] === runColor) {
                    runLen++;
                    if (runLen === 5) {
                        result += 3;
                    } else if (runLen > 5) {
                        result++;
                    }
                } else {
                    runColor = m.modules[i][j];
                    runLen = 1;
                }
            }
            runColor = false;
            runLen = 0;
            for (var k = 0; k < size; k++) {
                if (m.modules[k][i] === runColor) {
                    runLen++;
                    if (runLen === 5) {
                        result += 3;
                    } else if (runLen > 5) {
                        result++;
                    }
                } else {
                    runColor = m.modules[k][i];
                    runLen = 1;
                }
            }
        }

        // Regla 2: bloques 2x2 del mismo color
        for (var y2 = 0; y2 < size - 1; y2++) {
            for (var x2 = 0; x2 < size - 1; x2++) {
                var c = m.modules[y2][x2];
                if (c === m.modules[y2][x2 + 1]
                    && c === m.modules[y2 + 1][x2]
                    && c === m.modules[y2 + 1][x2 + 1]) {
                    result += 3;
                }
            }
        }

        // Regla 3: patron 1:1:3:1:1 que puede confundirse con un buscador
        result += finderLikePenalty(m);

        // Regla 4: desequilibrio entre modulos claros y oscuros
        var total = size * size;
        var k2 = Math.ceil(Math.abs(dark * 20 - total * 10) / total) - 1;
        result += k2 * 10;

        return result;
    }

    /**
     * Regla 3 de la norma: penaliza el patron oscuro-claro-oscuro(x3)-claro-oscuro
     * cuando va acompanado de 4 modulos claros a un lado, porque un lector puede
     * confundirlo con un patron de busqueda. Se recorre cada fila y cada columna
     * con el mismo accesor para no duplicar logica ni equivocar los limites.
     */
    function finderLikePenalty(m) {
        var size = m.size;
        var pattern = [true, false, true, true, true, false, true];
        var score = 0;

        function scanLine(get) {
            for (var i = 0; i + 7 <= size; i++) {
                var matches = true;
                for (var k = 0; k < 7; k++) {
                    if (get(i + k) !== pattern[k]) {
                        matches = false;
                        break;
                    }
                }
                if (!matches) {
                    continue;
                }

                // Fuera de la matriz cuenta como claro (zona de silencio).
                var clearBefore = true;
                for (var b = 1; b <= 4; b++) {
                    var ib = i - b;
                    if (ib >= 0 && get(ib)) {
                        clearBefore = false;
                        break;
                    }
                }
                var clearAfter = true;
                for (var a = 0; a < 4; a++) {
                    var ia = i + 7 + a;
                    if (ia < size && get(ia)) {
                        clearAfter = false;
                        break;
                    }
                }
                if (clearBefore || clearAfter) {
                    score += 40;
                }
            }
        }

        for (var y = 0; y < size; y++) {
            scanLine((function (row) {
                return function (i) { return m.modules[row][i]; };
            })(y));
        }
        for (var x = 0; x < size; x++) {
            scanLine((function (col) {
                return function (i) { return m.modules[i][col]; };
            })(x));
        }
        return score;
    }

    // --- API publica ---------------------------------------------------------

    function matrix(text, eccLevel) {
        var level = ECC[(eccLevel || 'M').toUpperCase()];
        if (!level) {
            throw new Error('Nivel de correccion invalido: ' + eccLevel);
        }

        var bytes = toUtf8Bytes(String(text));
        var ver = chooseVersion(bytes.length, level.idx);
        var data = buildCodewords(bytes, ver, level.idx);
        var codewords = addEccAndInterleave(data, ver, level.idx);

        var m = new Matrix(ver * 4 + 17);
        drawFunctionPatterns(m, ver);
        drawCodewords(m, codewords);

        // Elige la mascara con menor penalizacion.
        var bestMask = 0;
        var minPenalty = Infinity;
        for (var mask = 0; mask < 8; mask++) {
            applyMask(m, mask);
            drawFormatBits(m, level.bits, mask, false);
            var p = penaltyScore(m);
            if (p < minPenalty) {
                minPenalty = p;
                bestMask = mask;
            }
            applyMask(m, mask); // XOR de nuevo para deshacer
        }

        applyMask(m, bestMask);
        drawFormatBits(m, level.bits, bestMask, false);

        m.version = ver;
        m.mask = bestMask;
        return m;
    }

    function svg(text, options) {
        var opts = options || {};
        var border = opts.border == null ? 4 : opts.border;
        var dark = opts.dark || '#101c15';
        var light = opts.light || '#ffffff';

        var m = matrix(text, opts.ecc || 'M');
        var dim = m.size + border * 2;
        var path = [];

        for (var y = 0; y < m.size; y++) {
            for (var x = 0; x < m.size; x++) {
                if (m.modules[y][x]) {
                    path.push('M' + (x + border) + ',' + (y + border) + 'h1v1h-1z');
                }
            }
        }

        return '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ' + dim + ' ' + dim + '" '
            + 'shape-rendering="crispEdges" role="img" aria-label="Codigo QR">'
            + '<rect width="100%" height="100%" fill="' + light + '"/>'
            + '<path d="' + path.join('') + '" fill="' + dark + '"/>'
            + '</svg>';
    }

    global.QR = { svg: svg, matrix: matrix };

})(typeof window !== 'undefined' ? window : this);
