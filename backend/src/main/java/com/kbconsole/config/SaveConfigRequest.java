package com.kbconsole.config;

import com.kbconsole.config.dto.ConfigModels.ChunkConfig;
import com.kbconsole.config.dto.ConfigModels.ParseConfig;
import com.kbconsole.config.dto.ConfigModels.RetrievalConfig;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
public class SaveConfigRequest {
    private String indexStrategy;
    private ParseConfig parse;
    private ChunkConfig chunk;
    private RetrievalConfig retrieval;
    private String changeNote;
    private RebuildRequest rebuild;

    @Getter
    @Setter
    @NoArgsConstructor
    public static class RebuildRequest {
        private String scope;
        private List<Long> documentIds;
    }
}
