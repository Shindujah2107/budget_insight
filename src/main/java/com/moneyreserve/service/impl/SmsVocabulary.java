package com.moneyreserve.service.impl;

import com.moneyreserve.domain.BiSmsVocabularyWord;
import com.moneyreserve.dto.SmsVocabularyDto;
import com.moneyreserve.repository.BiSmsVocabularyWordRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Reads the SMS vocabulary. A few hundred rows, read when needed rather than cached: it changes
 * only by migration or publish, and a stale cache here would reject every skeleton that used a new
 * word until a restart.
 */
@Component
@RequiredArgsConstructor
public class SmsVocabulary {

  private final BiSmsVocabularyWordRepository words;

  /**
   * The version phones should hold: the newest version in which any word was added or retired
   * (0 if there are none). A retirement counts, so phones holding the retired word fetch again.
   */
  public int currentVersion() {
    return versionOf(words.findAllByOrderByIdAsc());
  }

  static int versionOf(List<BiSmsVocabularyWord> rows) {
    return rows.stream().mapToInt(BiSmsVocabularyWord::changedInVersion).max().orElse(0);
  }

  /** Words a phone on {@code version} may use: active, and added at or before that version. */
  public Map<Integer, String> usableAt(int version) {
    Map<Integer, String> out = new LinkedHashMap<>();
    for (BiSmsVocabularyWord w : words.findAllByOrderByIdAsc()) {
      if (Boolean.TRUE.equals(w.getIsActive()) && w.getAddedInVersion() <= version) {
        out.put(w.getId(), w.getWord());
      }
    }
    return out;
  }

  /** Every word ever issued, retired ones included, for rendering formats stored earlier. */
  public Map<Integer, String> all() {
    Map<Integer, String> out = new LinkedHashMap<>();
    for (BiSmsVocabularyWord w : words.findAllByOrderByIdAsc()) {
      out.put(w.getId(), w.getWord());
    }
    return out;
  }

  /** The current vocabulary for a phone: active words only. */
  public SmsVocabularyDto forDevice() {
    List<BiSmsVocabularyWord> all = words.findAllByOrderByIdAsc();
    int version = versionOf(all);
    return SmsVocabularyDto.builder()
        .version(version)
        .words(
            all.stream()
                .filter(w -> Boolean.TRUE.equals(w.getIsActive()))
                .map(w -> new SmsVocabularyDto.Word(w.getId(), w.getWord()))
                .toList())
        .build();
  }

  public List<BiSmsVocabularyWord> rows() {
    return words.findAllByOrderByIdAsc();
  }
}
