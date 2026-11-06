package com.example.demo.notification;

import com.example.demo.notification.NotificationPreference.Delivery;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface NotificationPreferenceRepository extends JpaRepository<NotificationPreference, Integer> {

    Optional<NotificationPreference> findByUserIdAndChannel(Integer userId, String channel);

    Optional<NotificationPreference> findByUnsubscribeToken(String token);

    List<NotificationPreference> findByChannelAndDeliveryIn(String channel, Collection<Delivery> deliveries);
}
