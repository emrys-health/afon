package health.emrys.afon.acceptance.tags;

import org.scalatest.TagAnnotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Tests exercising the projects: those of the central identity, a client and a group per project, and tokens that name the one project they are
 * for, in a KeyCloak container, independent of cloud; and those that a project asks of a cloud, which use the real tools of the cloud, and are
 * also tagged by it, {@link Aws}, {@link Azure} or {@link Gcp}, and are in the package of its specs.
 * Off by default, for now: run only with {@code -Pproject}, which selects the packages of the clouds too, for the specs that use their tools, which
 * must be logged in.  Filter with {@code -DtagsToInclude=health.emrys.afon.acceptance.tags.Project}.
 */
@TagAnnotation
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface Project {
}
