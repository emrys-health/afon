package health.emrys.afon.cdk;

import java.util.List;

/**
 * A part of a stack, eg Polaris or Jupyter, deployed to whatever cloud the {@link StackContext}'s platform is, and
 * taking its config from that cloud's own properties.  A stack is composed of components, which find what they
 * depend on, eg the DNS record of KeyCloak, among those deployed before them, see {@link StackContext#find(Class)}.
 * <p>
 * What a component needs of the others is declared by {@link #requires()} and {@link #after()}, so that a stack that
 * composes them wrongly fails when it is defined, with a message naming the problem, see
 * {@link StackDefinition#componentsFor}.
 */
public interface StackComponent {

    /**
     * Deploys the component's resources to the context's stack.
     */
    void deploy(StackContext context);

    /**
     * @return the components the stack must have, deployed before this one, as it cannot do without them
     */
    default List<Class<? extends StackComponent>> requires() {
        return List.of();
    }

    /**
     * @return the components that, if the stack has them, must be deployed before this one, as it uses them when
     * present, eg the KeyCloak whose DNS record Polaris waits for.  Unlike {@link #requires()}, the stack need not have them.
     */
    default List<Class<? extends StackComponent>> after() {
        return List.of();
    }
}
