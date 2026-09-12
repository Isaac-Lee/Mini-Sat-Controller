package msc.services.flightdynamics;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;

/**
 * A8: role, path-shape and Idempotency-Key parity with the existing FD access endpoint
 * ({@link OrbitApi#access}), checked structurally by reflection so it cannot silently drift from
 * the reference implementation. No database or Spring context is required.
 */
class IlluminationApiTest {
  private static Method accessMethod() throws NoSuchMethodException {
    return OrbitApi.class.getMethod(
        "access", OrbitApi.AccessRequest.class, String.class, Authentication.class);
  }

  private static Method targetIlluminationPost() throws NoSuchMethodException {
    return IlluminationApi.class.getMethod(
        "targetIllumination",
        IlluminationApi.TargetIlluminationRequest.class,
        String.class,
        Authentication.class);
  }

  private static Method spacecraftEclipsePost() throws NoSuchMethodException {
    return IlluminationApi.class.getMethod(
        "spacecraftEclipse", IlluminationApi.EclipseRequest.class, String.class, Authentication.class);
  }

  @Test
  void postEndpointsRequireTheSameRolesAsTheExistingAccessEndpoint() throws Exception {
    String referenceRoles = accessMethod().getAnnotation(PreAuthorize.class).value();
    assertNotNull(referenceRoles);
    assertEquals(referenceRoles, targetIlluminationPost().getAnnotation(PreAuthorize.class).value());
    assertEquals(referenceRoles, spacecraftEclipsePost().getAnnotation(PreAuthorize.class).value());
  }

  @Test
  void getEndpointsHaveNoExtraRoleRestrictionLikeTheExistingAccessReadEndpoint() throws Exception {
    var referenceGet = OrbitApi.class.getMethod("access", String.class); // GET /api/access-predictions/{id}
    assertNull(referenceGet.getAnnotation(PreAuthorize.class));

    var illuminationGet = IlluminationApi.class.getMethod("targetIlluminationResult", String.class);
    var eclipseGet = IlluminationApi.class.getMethod("spacecraftEclipseResult", String.class);
    assertNull(illuminationGet.getAnnotation(PreAuthorize.class));
    assertNull(eclipseGet.getAnnotation(PreAuthorize.class));
  }

  @Test
  void postEndpointsCarryAnIdempotencyKeyHeaderParameterLikeTheExistingAccessEndpoint()
      throws Exception {
    for (Method m : List.of(accessMethod(), targetIlluminationPost(), spacecraftEclipsePost()))
      assertTrue(
          Arrays.stream(m.getParameters())
              .anyMatch(
                  p -> {
                    var header = p.getAnnotation(RequestHeader.class);
                    return header != null && "Idempotency-Key".equals(header.value());
                  }),
          m.getName() + " must require an Idempotency-Key header, like the existing access endpoint");
  }

  @Test
  void postEndpointsExposeBothApiAndInternalPrefixesLikeTheExistingAccessEndpoint()
      throws Exception {
    for (Method m : List.of(targetIlluminationPost(), spacecraftEclipsePost())) {
      String[] paths = m.getAnnotation(PostMapping.class).value();
      assertTrue(Arrays.stream(paths).anyMatch(p -> p.startsWith("/api/")), m.getName());
      assertTrue(Arrays.stream(paths).anyMatch(p -> p.startsWith("/internal/")), m.getName());
    }
  }

  @Test
  void getEndpointsExposeBothApiAndInternalPrefixesLikeTheExistingAccessReadEndpoint()
      throws Exception {
    for (Method m :
        List.of(
            IlluminationApi.class.getMethod("targetIlluminationResult", String.class),
            IlluminationApi.class.getMethod("spacecraftEclipseResult", String.class))) {
      String[] paths = m.getAnnotation(GetMapping.class).value();
      assertTrue(Arrays.stream(paths).anyMatch(p -> p.startsWith("/api/")), m.getName());
      assertTrue(Arrays.stream(paths).anyMatch(p -> p.startsWith("/internal/")), m.getName());
    }
  }
}
