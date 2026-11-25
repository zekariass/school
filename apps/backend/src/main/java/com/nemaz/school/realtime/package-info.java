/**
 * Real-time gateway (Implementation Plan M2 step 5): STOMP over a plain WebSocket at
 * {@code /ws}, one in-memory broker per instance, Redis pub/sub between instances.
 * <ul>
 * <li><b>CONNECT</b> is authenticated with an access token ({@code Authorization: Bearer},
 * native clients) or a single-use 60-second ticket ({@code ticket}, browsers, from
 * {@code POST /api/v1/realtime/tickets}); both end in the same session and account checks as
 * HTTP requests. The STOMP principal name is the user id.</li>
 * <li>Clients may only SUBSCRIBE to their own {@code /user/queue/*} and may not SEND.</li>
 * <li>Sessions end with the user's session family ({@code SessionsRevoked}, broadcast to every
 * instance), and a local sweep every minute closes any that are no longer valid.</li>
 * <li>Other modules push with {@link com.nemaz.school.realtime.RealtimePublisher}.</li>
 * </ul>
 */
@ApplicationModule(displayName = "Realtime")
package com.nemaz.school.realtime;

import org.springframework.modulith.ApplicationModule;
