package com.trackam.ai;

import com.trackam.model.CustomCategory;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Builds the dynamic "Valid categories" section for AI parser prompts,
 * merging default categories with user-defined custom ones.
 */
public class CategoryPromptHelper {

    private static final String DEFAULT_EXPENSES =
            "transport, food, market, airtime, bills, health, education, supplies, personal, gifts, other_expense";
    private static final String DEFAULT_INCOME =
            "sales, momo, salary, other_income";

    public static String buildCategorySection(List<CustomCategory> customCategories) {
        StringBuilder sb = new StringBuilder();
        sb.append("Valid categories (use ONLY these):\n");

        // Expense line
        sb.append("Expense: ").append(DEFAULT_EXPENSES);
        String customExpenses = customCategories.stream()
                .filter(c -> "expense".equals(c.getType()))
                .map(CategoryPromptHelper::formatCustomCategory)
                .collect(Collectors.joining(", "));
        if (!customExpenses.isEmpty()) {
            sb.append(", ").append(customExpenses);
        }

        // Income line
        sb.append("\nIncome:  ").append(DEFAULT_INCOME);
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
