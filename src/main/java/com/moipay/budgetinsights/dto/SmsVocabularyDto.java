package com.moipay.budgetinsights.dto;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** The words a phone may name in a skeleton, served with reference sync when the phone is behind. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SmsVocabularyDto {

  private int version;

  private List<Word> words;

  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class Word {
    private int id;
    private String word;
  }
}
