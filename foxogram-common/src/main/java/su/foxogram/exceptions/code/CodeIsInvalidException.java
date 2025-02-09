package su.foxogram.exceptions.code;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;
import su.foxogram.constants.ExceptionsConstants;
import su.foxogram.exceptions.BaseException;

@ResponseStatus(HttpStatus.NOT_FOUND)
public class CodeIsInvalidException extends BaseException {

	public CodeIsInvalidException() {
		super(ExceptionsConstants.Messages.CODE_IS_INVALID.getValue(), CodeIsInvalidException.class.getAnnotation(ResponseStatus.class).value(), ExceptionsConstants.Code.IS_INVALID.getValue());
	}
}