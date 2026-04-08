package com.ridesharing.notification.service;

import com.ridesharing.notification.dto.NotificationMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @InjectMocks
    private NotificationService notificationService;

    @Test
    @DisplayName("Should send RIDE_ACCEPTED notification to rider and ride topic")
    void shouldNotifyRiderDriverAccepted() {
        notificationService.notifyRiderDriverAccepted(1L, 100L, 10L);

        ArgumentCaptor<NotificationMessage> captor = ArgumentCaptor.forClass(NotificationMessage.class);

        verify(messagingTemplate).convertAndSend(eq("/topic/rider/1"), captor.capture());
        verify(messagingTemplate).convertAndSend(eq("/topic/ride/100"), any(NotificationMessage.class));

        NotificationMessage sent = captor.getValue();
        assertEquals("RIDE_ACCEPTED", sent.getType());
        assertEquals("Driver Found!", sent.getTitle());
        assertEquals(100L, sent.getRideId());
        assertEquals(1L, sent.getRecipientId());
        assertEquals(10L, sent.getData().get("driverId"));
    }

    @Test
    @DisplayName("Should send RIDE_COMPLETED notification to both rider and driver")
    void shouldNotifyRideCompleted() {
        notificationService.notifyRideCompleted(1L, 10L, 100L, "105.00");

        verify(messagingTemplate).convertAndSend(eq("/topic/rider/1"), any(NotificationMessage.class));
        verify(messagingTemplate).convertAndSend(eq("/topic/driver/10"), any(NotificationMessage.class));
        verify(messagingTemplate).convertAndSend(eq("/topic/ride/100"), any(NotificationMessage.class));
    }

    @Test
    @DisplayName("Should send RIDE_CANCELLED to rider only when no driver assigned")
    void shouldNotifyRideCancelledNoDriver() {
        notificationService.notifyRideCancelled(1L, null, 100L, "No driver found");

        verify(messagingTemplate).convertAndSend(eq("/topic/rider/1"), any(NotificationMessage.class));
        verify(messagingTemplate).convertAndSend(eq("/topic/ride/100"), any(NotificationMessage.class));
        verify(messagingTemplate, never()).convertAndSend(contains("/topic/driver/"), any(NotificationMessage.class));
    }

    @Test
    @DisplayName("Should send RIDE_CANCELLED to both rider and driver when driver was assigned")
    void shouldNotifyRideCancelledWithDriver() {
        notificationService.notifyRideCancelled(1L, 10L, 100L, "Ride cancelled");

        verify(messagingTemplate).convertAndSend(eq("/topic/rider/1"), any(NotificationMessage.class));
        verify(messagingTemplate).convertAndSend(eq("/topic/driver/10"), any(NotificationMessage.class));
        verify(messagingTemplate).convertAndSend(eq("/topic/ride/100"), any(NotificationMessage.class));
    }

    @Test
    @DisplayName("Should send RIDE_STARTED notification to rider")
    void shouldNotifyRideStarted() {
        notificationService.notifyRideStarted(1L, 10L, 100L);

        ArgumentCaptor<NotificationMessage> captor = ArgumentCaptor.forClass(NotificationMessage.class);
        verify(messagingTemplate).convertAndSend(eq("/topic/rider/1"), captor.capture());

        assertEquals("RIDE_STARTED", captor.getValue().getType());
        assertEquals("Ride Started", captor.getValue().getTitle());
    }

    @Test
    @DisplayName("Should send APPROVED notification to driver")
    void shouldNotifyDriverApproved() {
        notificationService.notifyDriverApprovalStatus(10L, "APPROVED");

        ArgumentCaptor<NotificationMessage> captor = ArgumentCaptor.forClass(NotificationMessage.class);
        verify(messagingTemplate).convertAndSend(eq("/topic/driver/10"), captor.capture());

        NotificationMessage sent = captor.getValue();
        assertEquals("DRIVER_APPROVAL", sent.getType());
        assertTrue(sent.getMessage().contains("approved"));
        assertEquals("APPROVED", sent.getData().get("approvalStatus"));
    }

    @Test
    @DisplayName("Should send REJECTED notification to driver")
    void shouldNotifyDriverRejected() {
        notificationService.notifyDriverApprovalStatus(10L, "REJECTED");

        ArgumentCaptor<NotificationMessage> captor = ArgumentCaptor.forClass(NotificationMessage.class);
        verify(messagingTemplate).convertAndSend(eq("/topic/driver/10"), captor.capture());

        assertTrue(captor.getValue().getMessage().contains("rejected"));
    }
}
