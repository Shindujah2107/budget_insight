package com.moipay.budgetinsights.dto;

import java.util.List;

/** The vocabulary as the admin sees it, including retired words. */
public record AdminSmsVocabulary(int version, List<Word> words) {

  public record Word(int id, String word, String source, boolean active, int addedInVersion) {}
}
