package health.emrys.afon.acceptance.tags;

import org.scalatest.TagAnnotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Every test exercising the projects, of two kinds, which also have a tag of their own: {@link ProjectIdentity}, the central identity in a KeyCloak
 * container, which needs only docker, and {@link ProjectCloud}, what a project asks of a cloud, with the tools of the cloud, which must be logged in.
 * Off by default, for now: run only with {@code -Pproject}, which runs both kinds, or one of them, with {@code -Pidentity} or {@code -Pcloud}.
 */
@TagAnnotation
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface Project {
}
