package health.emrys.afon.acceptance.tags;

import org.scalatest.TagAnnotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * What a project asks of a cloud, with the real tools of the cloud (aws, az or gcloud), which must be logged in: the store of its state, and its names
 * accepted by the cloud.  A kind of {@link Project}: those specs have both tags, and the tag of their cloud, {@link Aws}, {@link Azure} or {@link Gcp}.
 * Select only them with {@code -Pcloud}.
 */
@TagAnnotation
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface ProjectCloud {
}
