package health.emrys.afon.cdk.component;

/**
 * A part of a stack, eg Polaris or Jupyter, deployed to whatever cloud the {@link StackContext}'s platform is, and
 * taking its config from that cloud's own properties.  A stack is composed of components, which find what they
 * depend on, eg the DNS record of KeyCloak, among those deployed before them, see {@link StackContext#find(Class)}.
 */
public interface StackComponent {

    /**
     * Deploys the component's resources to the context's stack.
     */
    void deploy(StackContext context);

}
