package su.foxogram.services;

import com.fasterxml.jackson.core.JsonProcessingException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import su.foxogram.constants.GatewayEventsConstants;
import su.foxogram.constants.MemberConstants;
import su.foxogram.constants.StorageConstants;
import su.foxogram.dtos.api.request.MessageCreateDTO;
import su.foxogram.dtos.api.response.MessageDTO;
import su.foxogram.exceptions.cdn.UploadFailedException;
import su.foxogram.exceptions.member.MissingPermissionsException;
import su.foxogram.exceptions.message.MessageNotFoundException;
import su.foxogram.models.Channel;
import su.foxogram.models.Member;
import su.foxogram.models.Message;
import su.foxogram.models.User;
import su.foxogram.repositories.ChannelRepository;
import su.foxogram.repositories.MessageRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
public class MessagesService {

	private final MessageRepository messageRepository;

	private final StorageService storageService;

	private final ProducerKafkaService producerKafkaService;

	private final ChannelRepository channelRepository;

	@Autowired
	public MessagesService(MessageRepository messageRepository, StorageService storageService, ProducerKafkaService producerKafkaService, ChannelRepository channelRepository) {
		this.messageRepository = messageRepository;
		this.storageService = storageService;
		this.producerKafkaService = producerKafkaService;
		this.channelRepository = channelRepository;
	}

	public List<MessageDTO> getMessages(long before, int limit, Channel channel) {
		List<Message> messagesArray = messageRepository.findAllByChannel(channel, limit);

		log.info("Messages ({}, {}) in channel ({}) found successfully", limit, before, channel.getId());

		return messagesArray.stream()
				.limit(limit)
				.map(MessageDTO::new)
				.collect(Collectors.toList());
	}

	public Message getMessage(long id, Channel channel) throws MessageNotFoundException {
		Message message = messageRepository.findByChannelAndId(channel, id);

		if (message == null) throw new MessageNotFoundException();

		log.info("Message ({}) in channel ({}) found successfully", id, channel.getId());

		return message;
	}

	public void addMessage(Channel channel, User user, MessageCreateDTO body, List<MultipartFile> attachments) throws UploadFailedException, JsonProcessingException {
		long authorId = user.getId();
		long timestamp = System.currentTimeMillis();
		List<String> uploadedAttachments = new ArrayList<>();
		String content = body.getContent();

		try {
			if (!attachments.isEmpty()) return;

			uploadedAttachments = attachments.stream()
					.map(attachment -> {
						try {
							return uploadAttachment(attachment);
						} catch (UploadFailedException e) {
							throw new RuntimeException(e);
						}
					})
					.collect(Collectors.toList());
		} catch (Exception e) {
			throw new UploadFailedException();
		} finally {
			Message message = new Message(0, channel, content, authorId, timestamp, uploadedAttachments);
			messageRepository.save(message);

			channel = channelRepository.findById(channel.getId()).get();
			List<Long> recipients = channel.getMembers().stream()
					.map(Member::getId)
					.collect(Collectors.toList());

			producerKafkaService.send(GatewayEventsConstants.Message.CREATED.getValue(), recipients, new MessageDTO(message));
			log.info("Message ({}) to channel ({}) created successfully", message.getId(), channel.getId());
		}
	}

	public void deleteMessage(long id, Member member, Channel channel) throws MessageNotFoundException, MissingPermissionsException {
		Message message = messageRepository.findByChannelAndId(channel, id);

		if (message == null) throw new MessageNotFoundException();
		message.isAuthor(member);
		member.hasAnyPermission(MemberConstants.Permissions.ADMIN, MemberConstants.Permissions.MANAGE_MESSAGES);

		messageRepository.delete(message);
		log.info("Message ({}) in channel ({}) deleted successfully", id, channel.getId());
	}

	public Message editMessage(long id, Channel channel, Member member, MessageCreateDTO body) throws MessageNotFoundException, MissingPermissionsException {
		Message message = messageRepository.findByChannelAndId(channel, id);
		String content = body.getContent();

		if (message == null) throw new MessageNotFoundException();
		message.isAuthor(member);

		message.setContent(content);
		messageRepository.save(message);
		log.info("Message ({}) in channel ({}) edited successfully", id, channel.getId());

		return message;
	}

	private String uploadAttachment(MultipartFile attachment) throws UploadFailedException {
		try {
			return storageService.uploadToMinio(attachment, StorageConstants.ATTACHMENTS_BUCKET);
		} catch (Exception e) {
			throw new UploadFailedException();
		}
	}
}
