import java.io.*;
import java.util.*;
import java.text.*;
import java.nio.file.*;

/*
 * Smart Banking Simulator - Single File (Syllabus-complete)
 * - File: accounts.dat (CSV: accNo,type,name,balance,features,extra)
 * - Log: statements.txt (appends readable transaction lines)
 *
 * Compile: javac SmartBankingApp.java
 * Run:     java SmartBankingApp
 */

// =================== CUSTOM EXCEPTIONS ===================
class NegativeAmountException extends Exception {
    public NegativeAmountException(String msg) { super(msg); }
}

class InsufficientFundsException extends Exception {
    public InsufficientFundsException(String msg) { super(msg); }
}

class AccountNotFoundException extends Exception {
    public AccountNotFoundException(String msg) { super(msg); }
}

// =================== INTERFACE ===================
interface BankOperations {
    void deposit(double amt) throws NegativeAmountException;
    void withdraw(double amt) throws NegativeAmountException, InsufficientFundsException;
    double calculateInterest(); // periodic interest amount (e.g., monthly)
}

// =================== ACCOUNT (ABSTRACT) ===================
abstract class Account implements BankOperations {
    protected int accNo;
    protected String name;
    protected double balance;
    protected int features; // bitwise flags
    protected List<String> statementBuffer = new ArrayList<>();

    // Feature flags
    public static final int OVERDRAFT = 1 << 0;   // 0001
    public static final int SMS_ALERT = 1 << 1;   // 0010
    public static final int PREMIUM   = 1 << 2;   // 0100

    public Account(int accNo, String name, double balance, int features) {
        this.accNo = accNo;
        this.name = name;
        this.balance = balance;
        this.features = features;
    }

    public int getAccNo() { return accNo; }
    public String getName() { return name; }
    public double getBalance() { return balance; }
    public int getFeatures() { return features; }

    public boolean hasFeature(int flag) {
        return (features & flag) != 0;
    }

    @Override
    public void deposit(double amt) throws NegativeAmountException {
        if (amt <= 0) throw new NegativeAmountException("Amount must be positive.");
        balance += amt;
        addStatement("Deposit", amt, balance);
    }

    @Override
    public void withdraw(double amt) throws NegativeAmountException, InsufficientFundsException {
        if (amt <= 0) throw new NegativeAmountException("Amount must be positive.");
        // Overdraft allowed only for accounts with OVERDRAFT flag
        if (amt > balance) {
            if (hasFeature(OVERDRAFT)) {
                // allow to negative up to -5000 for example
                double limit = -5000.0;
                if ((balance - amt) < limit) throw new InsufficientFundsException("Exceeded overdraft limit.");
            } else {
                throw new InsufficientFundsException("Insufficient balance.");
            }
        }
        balance -= amt;
        addStatement("Withdraw", amt, balance);
    }

    protected void addStatement(String type, double amt, double newBal) {
        String ts = BankingUtils.timestamp();
        statementBuffer.add(String.format("%s | %d | %s | %.2f | Bal: %.2f", ts, accNo, type, amt, newBal));
    }

    // Template Method for month-end processing
    public final void monthEndTemplate() {
        preMonth();
        double interest = calculateInterest();
        applyInterest(interest);
        postMonth();
    }

    // hooks - subclasses may override
    protected void preMonth() {}
    protected void applyInterest(double interest) {
        if (interest != 0) {
            balance += interest;
            addStatement("Interest", interest, balance);
        }
    }
    protected void postMonth() {}

    // For persistence CSV row
    public String toCSV() {
        // CSV fields: accNo,type,name,balance,features,extra
        return String.format("%d,%s,%s,%.6f,%d,%s",
                accNo, getType(), escapeCsv(name), balance, features, getExtra());
    }

    protected String escapeCsv(String s) {
        return s.replace(",", " "); // simple
    }

    // type string for persistence
    public abstract String getType();
    // extra field reserved (e.g., loan remaining months)
    protected String getExtra() { return ""; }

    // flush statements to file
    public void flushStatements(Path statementsFile) {
        if (statementBuffer.isEmpty()) return;
        try {
            Files.createDirectories(statementsFile.getParent() == null ? Paths.get(".") : statementsFile.getParent());
            try (BufferedWriter bw = Files.newBufferedWriter(statementsFile, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                for (String s : statementBuffer) {
                    bw.write(s);
                    bw.newLine();
                }
            }
            statementBuffer.clear();
        } catch (IOException e) {
            System.out.println("Could not write statements: " + e.getMessage());
        }
    }

    @Override
    public String toString() {
        return String.format("%d | %s | Bal: %.2f | Features: %s", accNo, name, balance, featureString());
    }

    private String featureString() {
        List<String> f = new ArrayList<>();
        if (hasFeature(OVERDRAFT)) f.add("OVERDRAFT");
        if (hasFeature(SMS_ALERT)) f.add("SMS");
        if (hasFeature(PREMIUM)) f.add("PREMIUM");
        return String.join(",", f);
    }
}

// =================== SavingsAccount ===================
class SavingsAccount extends Account {
    private double annualRate; // e.g., 0.04 for 4%

    public SavingsAccount(int accNo, String name, double balance, int features, double annualRate) {
        super(accNo, name, balance, features);
        this.annualRate = annualRate;
    }

    @Override
    public double calculateInterest() {
        // monthly interest approximation: balance * ( (1 + r/12) - 1 )
        double monthly = BankingUtils.compoundInterestComponent(balance, annualRate, 12, 1); // one month
        return monthly;
    }

    @Override
    public String getType() { return "SAVINGS"; }

    @Override
    protected String getExtra() {
        return String.format("rate=%.4f", annualRate);
    }
}

// =================== CurrentAccount ===================
class CurrentAccount extends Account {
    public CurrentAccount(int accNo, String name, double balance, int features) {
        super(accNo, name, balance, features);
    }

    @Override
    public double calculateInterest() {
        return 0; // usually no interest
    }

    @Override
    public String getType() { return "CURRENT"; }
}

// =================== LoanAccount ===================
class LoanAccount extends Account {
    private double annualRate;
    private int monthsRemaining;
    private double principal;

    public LoanAccount(int accNo, String name, double principal, int months, double annualRate, int features) {
        super(accNo, name, -principal, features); // store loan as negative balance (owed)
        this.principal = principal;
        this.monthsRemaining = months;
        this.annualRate = annualRate;
    }

    @Override
    public double calculateInterest() {
        // monthly interest on outstanding (negative balance magnitude)
        double outstanding = Math.abs(balance);
        double monthlyInterest = outstanding * (annualRate / 12.0);
        return monthlyInterest; // added to negative balance (treated below)
    }

    @Override
    protected void applyInterest(double interest) {
        // For loans, interest increases owed (balance more negative)
        if (interest != 0) {
            balance -= interest; // more negative
            addStatement("LoanInterest", interest, balance);
        }
    }

    @Override
    protected void postMonth() {
        if (monthsRemaining > 0) monthsRemaining--;
    }

    @Override
    public String getType() { return "LOAN"; }

    @Override
    protected String getExtra() {
        return String.format("months=%d,rate=%.4f", monthsRemaining, annualRate);
    }

    // repay a part of loan (positive payment reduces negative balance)
    public void repay(double amt) throws NegativeAmountException {
        if (amt <= 0) throw new NegativeAmountException("Amount must be positive.");
        balance += amt; // less negative
        addStatement("LoanRepay", amt, balance);
    }
}

// =================== ACCOUNT FACTORY (Factory Pattern) ===================
class AccountFactory {
    public static Account createAccount(int type, int accNo, String name, double amount, int features, Map<String,String> params) {
        // type: 1=Savings, 2=Current, 3=Loan
        switch (type) {
            case 1:
                double rate = params.containsKey("rate") ? Double.parseDouble(params.get("rate")) : 0.04;
                return new SavingsAccount(accNo, name, amount, features, rate);
            case 2:
                return new CurrentAccount(accNo, name, amount, features);
            case 3:
                // loan uses amount as principal
                int months = params.containsKey("months") ? Integer.parseInt(params.get("months")) : 12;
                double lrate = params.containsKey("rate") ? Double.parseDouble(params.get("rate")) : 0.12;
                return new LoanAccount(accNo, name, amount, months, lrate, features);
            default:
                return null;
        }
    }
}

// =================== BANK UTILITIES ===================
class BankingUtils {
    // timestamp helper
    public static String timestamp() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
    }

    // compute compound amount for fraction of year: component = P * ((1 + r/n)^(n*t) - 1)
    // Here we compute interest amount for t in years (e.g., t = 1/12 for one month)
    public static double compoundInterestComponent(double principal, double annualRate, int compoundsPerYear, double years) {
        if (annualRate <= 0 || principal <= 0) return 0.0;
        double base = 1.0 + annualRate / compoundsPerYear;
        double total = Math.pow(base, compoundsPerYear * years);
        return principal * (total - 1.0);
    }
}

// =================== BANK (SERVICE) ===================
class Bank {
    private Map<Integer, Account> accounts = new HashMap<>();
    private int nextAccNo = 1001;
    private final Path accountsFile = Paths.get("accounts.dat");
    private final Path statementsFile = Paths.get("statements.txt");

    public Bank() {
        loadFromFile();
    }

    public Account createAccount(int type, String name, double amount, int features, Map<String,String> params) throws NegativeAmountException {
        if (type != 3 && amount < 0) throw new NegativeAmountException("Initial deposit must be non-negative.");
        if (type == 3 && amount <= 0) throw new NegativeAmountException("Loan principal must be positive.");

        int accNo = nextAccNo++;
        Account acc = AccountFactory.createAccount(type, accNo, name, amount, features, params);
        accounts.put(accNo, acc);
        saveToFile();
        acc.addStatement("AccountCreated", amount, acc.getBalance());
        acc.flushStatements(statementsFile);
        return acc;
    }

    public Account getAccount(int accNo) throws AccountNotFoundException {
        Account a = accounts.get(accNo);
        if (a == null) throw new AccountNotFoundException("Account " + accNo + " not found.");
        return a;
    }

    public void deposit(int accNo, double amount) throws Exception {
        Account a = getAccount(accNo);
        a.deposit(amount);
        saveToFile();
        a.flushStatements(statementsFile);
    }

    public void withdraw(int accNo, double amount) throws Exception {
        Account a = getAccount(accNo);
        a.withdraw(amount);
        saveToFile();
        a.flushStatements(statementsFile);
    }

    public void transfer(int fromAcc, int toAcc, double amount) throws Exception {
        if (fromAcc == toAcc) throw new IllegalArgumentException("Cannot transfer to same account.");
        Account fa = getAccount(fromAcc);
        Account ta = getAccount(toAcc);

        // perform atomically
        fa.withdraw(amount);
        ta.deposit(amount);

        saveToFile();
        fa.flushStatements(statementsFile);
        ta.flushStatements(statementsFile);
    }

    public void repayLoan(int accNo, double amount) throws Exception {
        Account a = getAccount(accNo);
        if (!(a instanceof LoanAccount)) throw new IllegalArgumentException("Not a loan account.");
        ((LoanAccount)a).repay(amount);
        saveToFile();
        a.flushStatements(statementsFile);
    }

    public void listAccounts() {
        if (accounts.isEmpty()) {
            System.out.println("No accounts.");
            return;
        }
        for (Account a : accounts.values()) {
            System.out.println(a);
        }
    }

    public void showAccount(int accNo) throws AccountNotFoundException {
        Account a = getAccount(accNo);
        System.out.println(a);
    }

    // Month-end processing (Template method in Account)
    public void processMonthEndAll() {
        for (Account a : accounts.values()) {
            a.monthEndTemplate();
            a.flushStatements(statementsFile);
        }
        saveToFile();
        System.out.println("Month-end processing completed for all accounts.");
    }

    // =================== Persistence ===================
    private void saveToFile() {
        try (BufferedWriter bw = Files.newBufferedWriter(accountsFile, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            for (Account a : accounts.values()) {
                bw.write(a.toCSV());
                bw.newLine();
            }
        } catch (IOException e) {
            System.out.println("Error saving accounts file: " + e.getMessage());
        }
    }

    private void loadFromFile() {
        if (!Files.exists(accountsFile)) {
            // no prior data
            return;
        }
        try (BufferedReader br = Files.newBufferedReader(accountsFile)) {
            String line;
            int maxNo = nextAccNo - 1;
            while ((line = br.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                String[] parts = line.split(",", 6);
                // accNo,type,name,balance,features,extra
                int accNo = Integer.parseInt(parts[0]);
                String type = parts[1];
                String name = parts[2];
                double bal = Double.parseDouble(parts[3]);
                int features = Integer.parseInt(parts[4]);
                String extra = parts.length >= 6 ? parts[5] : "";

                Account a = null;
                if (type.equalsIgnoreCase("SAVINGS")) {
                    double rate = extractDouble(extra, "rate", 0.04);
                    a = new SavingsAccount(accNo, name, bal, features, rate);
                } else if (type.equalsIgnoreCase("CURRENT")) {
                    a = new CurrentAccount(accNo, name, bal, features);
                } else if (type.equalsIgnoreCase("LOAN")) {
                    int months = extractInt(extra, "months", 12);
                    double rate = extractDouble(extra, "rate", 0.12);
                    double principal = Math.abs(bal);
                    a = new LoanAccount(accNo, name, principal, months, rate, features);
                    // restore current negative balance
                    a.balance = bal;
                }

                if (a != null) {
                    accounts.put(accNo, a);
                    if (accNo > maxNo) maxNo = accNo;
                }
            }
            nextAccNo = maxNo + 1;
        } catch (IOException e) {
            System.out.println("Error loading accounts file: " + e.getMessage());
        }
    }

    private int extractInt(String extra, String key, int def) {
        try {
            for (String token : extra.split(",")) {
                token = token.trim();
                if (token.startsWith(key + "=")) {
                    return Integer.parseInt(token.split("=")[1]);
                }
            }
        } catch (Exception e) {}
        return def;
    }
    private double extractDouble(String extra, String key, double def) {
        try {
            for (String token : extra.split(",")) {
                token = token.trim();
                if (token.startsWith(key + "=")) {
                    return Double.parseDouble(token.split("=")[1]);
                }
            }
        } catch (Exception e) {}
        return def;
    }
}

// =================== MAIN APP / CLI ===================
public class SmartBankingApp {
    static Scanner sc = new Scanner(System.in);
    static Bank bank = new Bank();

    public static void main(String[] args) {
        System.out.println("Smart Banking Simulator (Syllabus-ready)");
        int choice;
        do {
            printMenu();
            choice = readInt("Choice: ");
            try {
                switch (choice) {
                    case 1 -> uiCreateAccount();
                    case 2 -> uiDeposit();
                    case 3 -> uiWithdraw();
                    case 4 -> uiTransfer();
                    case 5 -> uiListAccounts();
                    case 6 -> uiShowAccount();
                    case 7 -> uiRepayLoan();
                    case 8 -> uiProcessMonthEnd();
                    case 9 -> uiShowStatementsFile();
                    case 0 -> System.out.println("Goodbye!");
                    default -> System.out.println("Invalid choice.");
                }
            } catch (Exception e) {
                System.out.println("ERROR: " + e.getMessage());
            }
        } while (choice != 0);
    }

    private static void printMenu() {
        System.out.println("\n===== MENU =====");
        System.out.println("1. Create Account");
        System.out.println("2. Deposit");
        System.out.println("3. Withdraw");
        System.out.println("4. Transfer");
        System.out.println("5. List Accounts");
        System.out.println("6. Show Account Details");
        System.out.println("7. Repay Loan");
        System.out.println("8. Process Month-End (interest, template)");
        System.out.println("9. Show statements.txt (last 50 lines)");
        System.out.println("0. Exit");
    }

    private static void uiCreateAccount() throws Exception {
        System.out.println("Account Types: 1.Savings 2.Current 3.Loan");
        int type = readInt("Type: ");
        sc.nextLine(); // consume
        System.out.print("Name: ");
        String name = sc.nextLine();
        double amount = readDouble("Amount (deposit or loan principal): ");
        int features = 0;
        System.out.println("Features: 1) Overdraft  2) SMS alerts  3) Premium");
        System.out.print("Enter feature numbers separated by space (or empty): ");
        String line = sc.nextLine().trim();
        if (!line.isEmpty()) {
            for (String tok : line.split("\\s+")) {
                switch (tok) {
                    case "1" -> features |= Account.OVERDRAFT;
                    case "2" -> features |= Account.SMS_ALERT;
                    case "3" -> features |= Account.PREMIUM;
                }
            }
        }

        Map<String,String> params = new HashMap<>();
        if (type == 1) {
            String r = readLineDefault("Annual rate (e.g., 0.04) [default 0.04]: ", "0.04");
            params.put("rate", r);
        } else if (type == 3) {
            String months = readLineDefault("Months for loan [default 12]: ", "12");
            String rate = readLineDefault("Annual rate for loan [default 0.12]: ", "0.12");
            params.put("months", months);
            params.put("rate", rate);
        }

        Account a = bank.createAccount(type, name, amount, features, params);
        System.out.println("Created: " + a);
    }

    private static void uiDeposit() throws Exception {
        int acc = readInt("Account No: ");
        double amt = readDouble("Amount: ");
        bank.deposit(acc, amt);
        System.out.println("Deposit successful.");
    }

    private static void uiWithdraw() throws Exception {
        int acc = readInt("Account No: ");
        double amt = readDouble("Amount: ");
        bank.withdraw(acc, amt);
        System.out.println("Withdraw successful.");
    }

    private static void uiTransfer() throws Exception {
        int from = readInt("From Acc: ");
        int to = readInt("To Acc: ");
        double amt = readDouble("Amount: ");
        bank.transfer(from, to, amt);
        System.out.println("Transfer successful.");
    }

    private static void uiRepayLoan() throws Exception {
        int acc = readInt("Loan Account No: ");
        double amt = readDouble("Payment Amount: ");
        bank.repayLoan(acc, amt);
        System.out.println("Loan repayment processed.");
    }

    private static void uiListAccounts() {
        bank.listAccounts();
    }

    private static void uiShowAccount() throws Exception {
        int acc = readInt("Account No: ");
        bank.showAccount(acc);
    }

    private static void uiProcessMonthEnd() {
        bank.processMonthEndAll();
    }

    private static void uiShowStatementsFile() {
        Path p = Paths.get("statements.txt");
        if (!Files.exists(p)) {
            System.out.println("No statements found.");
            return;
        }
        try {
            List<String> lines = Files.readAllLines(p);
            int start = Math.max(0, lines.size() - 50);
            for (int i = start; i < lines.size(); i++) System.out.println(lines.get(i));
        } catch (IOException e) {
            System.out.println("Could not read statements: " + e.getMessage());
        }
    }

    // ========== helpers ==========
    private static int readInt(String prompt) {
        while (true) {
            try {
                System.out.print(prompt);
                String s = sc.nextLine().trim();
                return Integer.parseInt(s);
            } catch (Exception e) {
                System.out.println("Invalid integer. Try again.");
            }
        }
    }

    private static double readDouble(String prompt) {
        while (true) {
            try {
                System.out.print(prompt);
                String s = sc.nextLine().trim();
                return Double.parseDouble(s);
            } catch (Exception e) {
                System.out.println("Invalid number. Try again.");
            }
        }
    }

    private static String readLineDefault(String prompt, String def) {
        System.out.print(prompt);
        String s = sc.nextLine().trim();
        return s.isEmpty() ? def : s;
    }
}