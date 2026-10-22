/**
 * match 的出入参 DTO（契约 §8）。
 *
 * <p>这里不出现任何引擎类型（{@code MatchCandidate} / {@code MatchQuery} 等）：web 层只认本包 DTO，
 * 引擎的类型边界止于 {@code match.service}，于是换实现不需要碰契约与前端。
 */
package com.findit.campus.match.web.dto;
