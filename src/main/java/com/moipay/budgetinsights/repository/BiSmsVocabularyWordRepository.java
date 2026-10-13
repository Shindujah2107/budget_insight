package com.moipay.budgetinsights.repository;

import com.moipay.budgetinsights.domain.BiSmsVocabularyWord;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BiSmsVocabularyWordRepository extends JpaRepository<BiSmsVocabularyWord, Integer> {

  List<BiSmsVocabularyWord> findAllByOrderByIdAsc();
}
