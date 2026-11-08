package com.kbconsole.config;

import com.kbconsole.config.dto.ConfigModels.ChunkConfig;
import com.kbconsole.config.dto.ConfigModels.ParseConfig;
import com.kbconsole.config.dto.ConfigModels.RetrievalConfig;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class ConfigPayload {
    private ParseConfig parse;
    private ChunkConfig chunk;
    private RetrievalConfig retrieval;
}
