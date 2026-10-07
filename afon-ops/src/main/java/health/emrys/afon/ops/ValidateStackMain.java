package health.emrys.afon.ops;

import health.emrys.afon.cdk.CloudStackValidator;
import health.emrys.afon.config.StackId;

/**
 * Validates the config of a stack, via {@link CloudStackValidator}.
 * The stack is given in kebab case, with or without the "stack" suffix, eg {@code azure-polaris},
 * or as the class simple name, eg {@code AzurePolarisStack}.  With no stack, it lists the stacks.
 */
public class ValidateStackMain {

    public static void main(String[] args) {
        CloudStackValidator validator = new CloudStackValidator();
        if (args.length != 1) {
            printUsage(validator);
            return;
        }
        StackId stack = validator.resolve(args[0]);
        if (stack == null) {
            System.out.println("Stack not found: " + args[0]);
            printUsage(validator);
            System.exit(1);
            return;
        }
        String stackDir;
        try {
            stackDir = validator.validate(stack);
        } catch (RuntimeException e) {
            // printed as well as logged, as the console logging may be off, eg in the CLI
            System.err.printf("Failed to validate stack %s: %s%n", stack, e);
            System.exit(1);
            return;
        }
        System.out.printf("Validated stack %s by synthesising it into %s%n", stack, stackDir);
    }

    private static void printUsage(CloudStackValidator validator) {
        System.out.println("Usage: validate-stack <stack>, where <stack> is one of:");
        validator.stackNames().forEach(name -> System.out.println("  " + name));
    }

}
