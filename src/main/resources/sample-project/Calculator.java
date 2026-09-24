package sample;

/**
 * Sample Calculator class for demonstration with the AI Agent.
 * Try asking the AI Agent to:
 * 1. "Add a square root and power method"
 * 2. "Refactor this code to handle division by zero properly"
 * 3. "Write JUnit 5 test cases for this Calculator"
 */
public class Calculator {

    public double add(double a, double b) {
        return a + b;
    }

    public double subtract(double a, double b) {
        return a - b;
    }

    public double multiply(double a, double b) {
        return a * b;
    }

    public double divide(double a, double b) {
        if (b == 0) {
            throw new IllegalArgumentException("Cannot divide by zero");
        }
        return a / b;
    }

    public static void main(String[] args) {
        Calculator calc = new Calculator();
        System.out.println("10 + 5 = " + calc.add(10, 5));
        System.out.println("10 / 2 = " + calc.divide(10, 2));
    }
}
