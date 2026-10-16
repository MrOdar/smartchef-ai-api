package com.smartchef.api.service;

import com.smartchef.api.dto.GeminiRecipeResult;
import com.smartchef.api.entity.ScanSession;
import com.smartchef.api.repository.ScanSessionRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Siembra al arrancar la sesion de demostracion que alimenta el boton
 * "Probar con foto demo".
 *
 * <p>Las recetas son fijas y estan escritas a mano con la misma riqueza que
 * exige el prompt de Gemini: asi el boton responde al instante, no consume
 * cuota de la API y <strong>nunca falla delante de un evaluador</strong>,
 * aunque Google este caido o la API key agotada.
 *
 * <p>Se persisten como filas reales (no como JSON fijo en el controlador) para
 * que tengan identificadores validos y el flujo de "Guardar en Favoritos"
 * funcione de verdad. La sesion lleva {@code user_id} nulo, asi que no aparece
 * en el historial de ningun usuario.
 */
@Component
public class DemoDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    /** Ingredientes visibles en static/assets/demo-fridge.jpg. */
    static final List<String> DEMO_INGREDIENTS = List.of(
            "tomate", "huevo", "cebolla", "queso curado", "pimiento rojo",
            "pimiento amarillo", "pimiento verde", "lechuga", "zanahoria",
            "calabacin", "brocoli", "limon", "leche");

    private final ScanSessionRepository scanSessionRepository;
    private final RecipeService recipeService;

    public DemoDataSeeder(ScanSessionRepository scanSessionRepository,
                          RecipeService recipeService) {
        this.scanSessionRepository = scanSessionRepository;
        this.recipeService = recipeService;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        List<ScanSession> existing = scanSessionRepository.findDemoSessions();

        if (!existing.isEmpty()) {
            log.info("Sesion demo ya sembrada (id {}); no se duplica", existing.get(0).getId());
            return;
        }

        ScanSession seeded = recipeService.seedDemoSession(demoResult());
        log.info("Sesion demo sembrada con id {} y {} recetas",
                seeded.getId(), seeded.getRecipes().size());
    }

    /** Resultado precalculado, con la misma forma que devolveria Gemini. */
    static GeminiRecipeResult demoResult() {
        return new GeminiRecipeResult(DEMO_INGREDIENTS, List.of(
                pistoConHuevo(), tortillaDeCalabacin(), ensaladaTibiaDeBrocoli()));
    }

    private static GeminiRecipeResult.GeneratedRecipe pistoConHuevo() {
        return new GeminiRecipeResult.GeneratedRecipe(
                "Pisto casero con huevo escalfado",
                """
                Un pisto de verduras cocinado a fuego lento hasta que el tomate se concentra \
                y endulza de forma natural. El huevo escalfado por encima aporta una yema \
                cremosa que se mezcla con las verduras al romperla. Es el plato perfecto para \
                aprovechar los pimientos y el calabacin antes de que pierdan firmeza.""",
                45,
                "MEDIA",
                420,
                List.of(
                        "Corta la cebolla, los pimientos y el calabacin en dados de un centimetro, "
                                + "procurando que queden del mismo tamano para que se cocinen por igual.",
                        "Pocha la cebolla con dos cucharadas de aceite a fuego suave durante 8 minutos, "
                                + "sin que llegue a dorarse: debe quedar transparente y dulce.",
                        "Anade los pimientos y sofrie 10 minutos mas a fuego medio, removiendo cada "
                                + "par de minutos para que no se peguen al fondo.",
                        "Incorpora el calabacin y el tomate troceado, baja a fuego suave y deja reducir "
                                + "20 minutos destapado hasta que el liquido se evapore casi por completo.",
                        "Escalfa los huevos 3 minutos en agua a 90 grados con un chorrito de vinagre, "
                                + "y sirvelos sobre el pisto caliente con sal en escamas."),
                "Si el tomate te queda acido, anade media cucharadita de azucar al final de la reduccion: "
                        + "equilibra el conjunto mejor que anadir mas sal.",
                List.of(
                        ing("tomate", "4 unidades maduras", true),
                        ing("pimiento rojo", "1 unidad", true),
                        ing("pimiento verde", "1 unidad", true),
                        ing("calabacin", "1 unidad mediana", true),
                        ing("cebolla", "1 unidad grande", true),
                        ing("huevo", "2 unidades", true),
                        ing("aceite de oliva virgen extra", "2 cucharadas", false),
                        ing("vinagre de vino", "1 chorrito", false)));
    }

    private static GeminiRecipeResult.GeneratedRecipe tortillaDeCalabacin() {
        return new GeminiRecipeResult.GeneratedRecipe(
                "Tortilla jugosa de calabacin y queso curado",
                """
                Una tortilla de interior cremoso y bordes apenas dorados, con el calabacin \
                pochado hasta quedar tierno y el queso curado fundiendose en vetas. Se come \
                igual de bien templada que fria, asi que sirve tanto para una cena rapida \
                como para la fiambrera del dia siguiente.""",
                25,
                "FACIL",
                380,
                List.of(
                        "Lamina el calabacin en rodajas finas de 3 milimetros y salalas ligeramente; "
                                + "dejalas reposar 5 minutos para que suelten agua.",
                        "Seca las rodajas con papel de cocina y pochalas a fuego medio 8 minutos, "
                                + "hasta que esten tiernas pero sin dorarse.",
                        "Bate 4 huevos con una pizca de sal hasta que la clara y la yema se integren "
                                + "por completo, y mezclalos con el calabacin y el queso rallado.",
                        "Cuaja la mezcla en una sarten antiadherente a fuego medio-bajo durante "
                                + "4 minutos, moviendo la sarten en circulos para que no se agarre.",
                        "Da la vuelta con ayuda de un plato y cuaja 2 minutos mas por el otro lado: "
                                + "el centro debe quedar jugoso al cortarla."),
                "Retirala del fuego cuando aun tiemble ligeramente en el centro; el calor residual "
                        + "termina de cuajarla y evita que quede seca.",
                List.of(
                        ing("calabacin", "1 unidad mediana", true),
                        ing("huevo", "4 unidades", true),
                        ing("queso curado", "60 g rallado", true),
                        ing("cebolla", "media unidad", true),
                        ing("aceite de oliva", "1 cucharada", false)));
    }

    private static GeminiRecipeResult.GeneratedRecipe ensaladaTibiaDeBrocoli() {
        return new GeminiRecipeResult.GeneratedRecipe(
                "Ensalada tibia de brocoli, zanahoria y limon",
                """
                Una ensalada templada que juega con el contraste entre el brocoli al dente y \
                la zanahoria crujiente en juliana. El aliño de limon y aceite se emulsiona con \
                el calor residual de las verduras y las envuelve sin apelmazarlas. Ligera, \
                rapida y pensada para dar salida a las verduras que quedan sueltas en el cajon.""",
                20,
                "FACIL",
                260,
                List.of(
                        "Separa el brocoli en floretes del mismo tamano y corta la zanahoria "
                                + "en juliana fina con un pelador.",
                        "Escalda el brocoli 4 minutos en agua hirviendo con sal: debe quedar al dente "
                                + "y de color verde intenso, no blando.",
                        "Cortalo en un bano de agua con hielo durante 1 minuto para fijar el color, "
                                + "y escurrelo bien para que el aliño no se aguade.",
                        "Emulsiona el zumo de medio limon con tres cucharadas de aceite y una pizca "
                                + "de sal, batiendo con un tenedor hasta que ligue.",
                        "Mezcla las verduras aun tibias con el aliño y la lechuga troceada, "
                                + "y sirve de inmediato con ralladura de limon por encima."),
                "Alina siempre con las verduras tibias y no frias: absorben mucho mejor el limon "
                        + "y el plato gana sabor sin necesidad de mas sal.",
                List.of(
                        ing("brocoli", "1 unidad pequena", true),
                        ing("zanahoria", "2 unidades", true),
                        ing("lechuga", "4 hojas", true),
                        ing("limon", "1 unidad", true),
                        ing("aceite de oliva virgen extra", "3 cucharadas", false),
                        ing("semillas de sesamo", "1 cucharada", false)));
    }

    private static GeminiRecipeResult.GeneratedIngredient ing(String name, String qty, boolean available) {
        return new GeminiRecipeResult.GeneratedIngredient(name, qty, available);
    }
}
