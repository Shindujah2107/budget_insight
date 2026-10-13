package com.moipay.budgetinsights.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** The transactions behind one expanded category row. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CategoryTransactionsResponse {

  private UUID categoryId;
  private String code;
  private String name;
  private String total;
  private PageDto page;
  private List<TransactionDto> transactions;

  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class PageDto {
    private Integer number;
    private Integer size;
    private Long totalElements;
    private Integer totalPages;
  }
}
