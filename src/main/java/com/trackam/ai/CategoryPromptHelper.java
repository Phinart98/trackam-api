package com.trackam.ai;

import com.trackam.model.CustomCategory;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Builds the dynamic "Valid categories" section for AI parser prompts,
 * merging default categories with user-defined custom ones.
 */
public class CategoryPromptHelper {

    private static final String DEFAULT_EXPENSE_SECTION =
            "transport (trotro, taxi, fuel, bus fares)\n" +
            "  food (groceries, restaurant, chop bar, cooking ingredients)\n" +
            "  market (buying goods at market or shop for personal use)\n" +
            "  airtime (mobile credit, data bundles, SIM top-ups)\n" +
            "  bills (electricity, water, rent, WiFi, utilities)\n" +
            "  health (hospital, pharmacy, medicine, doctor)\n" +
            "  education (school fees, books, tutoring, courses)\n" +
            "  supplies (raw materials or stock bought FOR BUSINESS/RESALE — e.g. fabric to sell, wholesale items)\n" +
            "  personal (personal purchases NOT for resale — clothing, shoes, personal electronics like a phone or laptop, haircut, personal care, items for family)\n" +
            "  gifts (gifts to others, donations, church offering, wedding/funeral contributions)\n" +
            "  other_expense (anything not fitting above)";

    private static final String DEFAULT_INCOME =
            "sales, momo, salary, other_income";

    public static String buildCategorySection(List<CustomCategory> customCategories) {
        StringBuilder sb = new StringBuilder();
        sb.append("Valid categories with usage guidance (use ONLY these IDs):\n");
        sb.append("Expense:\n  ").append(DEFAULT_EXPENSE_SECTION);
        String customExpenses = customCategories.stream()
                .filter(c -> "expense".equals(c.getType()))
                .map(CategoryPromptHelper::formatCustomCategory)
                .collect(Collectors.joining(", "));
        if (!customExpenses.isEmpty()) {
            sb.append("\n  ").append(customExpenses);
        }

        sb.append("\nIncome:\n  ").append(DEFAULT_INCOME);
        String customIncome = customCategories.stream()
                .filter(c -> "income".equals(c.getType()))
                .map(CategoryPromptHelper::formatCustomCategory)
                .collect(Collectors.joining(", "));
        if (!customIncome.isEmpty()) {
            sb.append(", ").append(customIncome);
        }

        return sb.toString();
    }

    private static String formatCustomCategory(CustomCategory cat) {
        if (cat.getKeywords() != null && !cat.getKeywords().isEmpty()) {
            return cat.getId() + " (keywords: " + String.join(", ", cat.getKeywords()) + ")";
        }
        return cat.getId();
    }
}
