package com.moneyreserve.service.impl;

import com.moneyreserve.domain.BiBankParserTemplate;
import com.moneyreserve.domain.BiSmsFormatCandidate;
import com.moneyreserve.domain.BiSmsSenderAllowlist;
import com.moneyreserve.repository.BiBankParserTemplateRepository;
import com.moneyreserve.repository.BiSmsSenderAllowlistRepository;
import com.moneyreserve.service.ingest.SkeletonGrammar;
import com.moneyreserve.service.ingest.TemplatePatternValidator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Whether a live template already reads a discovered format.
 *
 * <p>The test is the one a phone applies when it re-reads its queue: build the sample SMS from the
 * format (invented values in the right shapes) and run the published templates over it. Shared by
 * the admin list ("covered by …") and the daily maintenance pass (which records RESOLVED).
 */
@Component
@RequiredArgsConstructor
public class FormatCoverage {

  private final BiSmsSenderAllowlistRepository senders;
  private final BiBankParserTemplateRepository templates;
  private final SmsVocabulary vocabulary;

  /** A checker over the live allowlist and templates, loaded lazily, for one request or pass. */
  public Checker checker() {
    return new Checker();
  }

  /** The sender → bank map from the live allowlist, keyed in upper case. */
  public Map<String, String> bankBySender() {
    Map<String, String> out = new HashMap<>();
    for (BiSmsSenderAllowlist s : senders.findAllByIsActiveTrue()) {
      out.put(s.getSenderId().toUpperCase(Locale.ROOT), s.getBankCode());
    }
    return out;
  }

  public final class Checker {
    private Map<String, String> bankBySender;
    private Map<Integer, String> words;
    private List<Compiled> compiled;

    private record Compiled(UUID id, String bankCode, Pattern pattern) {}

    private Checker() {}

    public String bankOf(String senderId) {
      if (bankBySender == null) {
        bankBySender = FormatCoverage.this.bankBySender();
      }
      return bankBySender.get(senderId.toUpperCase(Locale.ROOT));
    }

    /** The candidate's skeleton filled with invented values. */
    public String sampleOf(BiSmsFormatCandidate c) {
      if (words == null) {
        words = vocabulary.all();
      }
      return SkeletonGrammar.synthetic(SkeletonGrammar.tokensOf(c.getTokenKey()), words);
    }

    /**
     * A live template of the sender's bank that reads the format. None for a sender that is not
     * on the allowlist: phones do not read such a sender at all, so no template covers it yet.
     */
    public UUID templateReading(BiSmsFormatCandidate c) {
      if (compiled == null) {
        compiled = new ArrayList<>();
        for (BiBankParserTemplate t : templates.findAllByIsActiveTrue()) {
          Pattern p = TemplatePatternValidator.compileQuietly(t.getPattern());
          if (p != null) {
            compiled.add(new Compiled(t.getId(), t.getBankCode(), p));
          }
        }
      }
      String bank = bankOf(c.getSenderId());
      if (bank == null) {
        return null;
      }
      String sample = sampleOf(c);
      for (Compiled t : compiled) {
        if (bank.equals(t.bankCode()) && t.pattern().matcher(sample).find()) {
          return t.id();
        }
      }
      return null;
    }
  }
}
