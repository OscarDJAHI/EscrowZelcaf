package com.zlecaf.escrow.web;

import com.zlecaf.escrow.domain.Company;
import com.zlecaf.escrow.domain.Role;
import com.zlecaf.escrow.domain.User;
import com.zlecaf.escrow.domain.WebhookSubscription;
import com.zlecaf.escrow.repository.UserRepository;
import com.zlecaf.escrow.repository.WebhookSubscriptionRepository;
import com.zlecaf.escrow.security.AuthPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cloisonnement par société des abonnements webhook — correctif de la rétrospective
 * Epic 1 (2026-07-27).
 *
 * <p>L'endpoint n'avait <b>aucun test</b> ; c'est la raison de fond pour laquelle
 * deux défauts inter-tenants y ont survécu à un epic entier consacré à la sécurité.
 * La revue de la Story 1.10 les a vus et les a classés « préexistant, hors
 * périmètre » — correct au sens de la story, faux au sens de l'epic.
 *
 * <p>Les deux propriétés prouvées ici sont asymétriques et doivent le rester :
 * en <b>lecture</b>, ne rendre que sa société ; en <b>écriture</b>, ne pas laisser
 * l'appelant nommer la société du tout. La seconde est la plus importante — un
 * abonnement créé pour une société tierce vers une URL choisie par l'attaquant
 * exfiltre les changements d'état, là où la lecture ne divulgue qu'une
 * configuration.
 */
class WebhookControllerTenantScopeTest {

    private static final AuthPrincipal ALICE = new AuthPrincipal(7L, "alice@acme.example", Role.BUYER);

    private WebhookSubscriptionRepository subscriptions;
    private UserRepository users;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        subscriptions = mock(WebhookSubscriptionRepository.class);
        users = mock(UserRepository.class);
        mvc = standaloneSetup(new WebhookController(subscriptions, users))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(fixedPrincipal(ALICE))
                .build();
    }

    @Test
    @DisplayName("Lecture : la liste est bornée à la société de l'appelant, jamais findAll()")
    void listIsScopedToTheCallersCompany() throws Exception {
        givenCallerBelongsTo(42L);
        when(subscriptions.findByCompanyId(42L)).thenReturn(List.of(subscription(1L, 42L, "https://acme.example/hook")));

        mvc.perform(get("/api/v1/webhooks/subscriptions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].companyId").value(42))
                .andExpect(jsonPath("$[0].targetUrl").value("https://acme.example/hook"));

        // L'assertion qui compte vraiment : le chemin non borné n'est plus emprunté.
        // Sans elle, réintroduire `findAll()` puis filtrer en mémoire passerait au vert
        // tout en rechargeant chaque URL de rappel du dépôt dans le processus.
        verify(subscriptions, never()).findAll();
    }

    @Test
    @DisplayName("Écriture : la société vient du JWT — le corps ne peut plus la nommer")
    void createDerivesTheCompanyFromTheTokenAndIgnoresTheBody() throws Exception {
        givenCallerBelongsTo(42L);
        when(subscriptions.save(any(WebhookSubscription.class)))
                .thenAnswer(inv -> inv.getArgument(0, WebhookSubscription.class));

        // Le corps porte un `companyId` de société tierce : c'est exactement la requête
        // qui, avant ce correctif, abonnait Alice aux événements de la société 999 vers
        // sa propre URL. Le champ n'existe plus dans le record, donc Jackson l'ignore.
        mvc.perform(post("/api/v1/webhooks/subscriptions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"companyId": 999,
                                 "targetUrl": "https://attacker.example/collect",
                                 "secretKey": "0123456789abcdef0123456789abcdef",
                                 "eventType": "escrow.released"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.companyId").value(42));

        var saved = org.mockito.ArgumentCaptor.forClass(WebhookSubscription.class);
        verify(subscriptions).save(saved.capture());
        assertThat(saved.getValue().getCompanyId())
                .as("la société persistée est celle du jeton, jamais celle du corps")
                .isEqualTo(42L);
        assertThat(saved.getValue().getEventType())
                .as("l'événement reste normalisé en majuscules")
                .isEqualTo("ESCROW.RELEASED");
    }

    @Test
    @DisplayName("Appelant sans société : lecture vide, écriture refusée sans rien révéler")
    void callerWithoutACompanyCanNeitherReadNorWrite() throws Exception {
        givenCallerBelongsTo(null);

        mvc.perform(get("/api/v1/webhooks/subscriptions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        mvc.perform(post("/api/v1/webhooks/subscriptions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"targetUrl": "https://nowhere.example/hook",
                                 "secretKey": "0123456789abcdef0123456789abcdef",
                                 "eventType": "escrow.released"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("Subscriber must belong to a company"));

        verify(subscriptions, never()).save(any());
        verify(subscriptions, never()).findAll();
    }

    @Test
    @DisplayName("Jeton valide dont l'utilisateur a disparu : traité comme sans société, pas en erreur")
    void aTokenWhoseUserVanishedIsTreatedAsCompanyless() throws Exception {
        when(users.findById(eq(7L))).thenReturn(Optional.empty());

        mvc.perform(get("/api/v1/webhooks/subscriptions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        verify(subscriptions, never()).findAll();
    }

    // --- fixtures ------------------------------------------------------------------

    private void givenCallerBelongsTo(Long companyId) {
        User user = new User();
        user.setId(ALICE.userId());
        user.setEmail(ALICE.email());
        if (companyId != null) {
            Company company = new Company();
            company.setId(companyId);
            user.setCompany(company);
        }
        when(users.findById(eq(ALICE.userId()))).thenReturn(Optional.of(user));
    }

    private static WebhookSubscription subscription(Long id, Long companyId, String url) {
        WebhookSubscription sub = new WebhookSubscription();
        sub.setId(id);
        sub.setCompanyId(companyId);
        sub.setTargetUrl(url);
        sub.setEventType("ESCROW.RELEASED");
        sub.setActive(true);
        return sub;
    }

    /** Résout {@code @AuthenticationPrincipal} sans monter de contexte de sécurité. */
    private static HandlerMethodArgumentResolver fixedPrincipal(AuthPrincipal principal) {
        return new HandlerMethodArgumentResolver() {
            @Override
            public boolean supportsParameter(MethodParameter parameter) {
                return AuthPrincipal.class.equals(parameter.getParameterType());
            }

            @Override
            public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                          NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
                return principal;
            }
        };
    }
}
