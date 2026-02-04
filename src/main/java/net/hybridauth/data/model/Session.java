package net.hybridauth.data.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.sql.Timestamp;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Session {
    private long id;
    private UUID userUuid;
    private String playerIp;
    private Timestamp loginTime;
    private Timestamp lastActivity;
    private boolean active;
}

